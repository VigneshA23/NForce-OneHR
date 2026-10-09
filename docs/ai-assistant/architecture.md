# Architecture

## One turn, end to end

```
POST /api/ai-assistant/chat   { message, conversationId?, currentPageId? }
        │
        │  JWT already required — SecurityConfig's .anyRequest().authenticated()
        │  covers this path, so no SecurityConfig change was needed.
        ▼
AiAssistantController          passes principal.getName() (the email) and nothing else
        ▼
AiAssistantService
   1. buildContext(email)      user, roles, audience buckets, shell role — all from the DB
   2. guards                   disabled? empty? too long? over the hourly budget?
   3. NavigationValidator      re-validate currentPageId; a spoofed one is dropped
   4. VectorKnowledgeRetriever embed the question, kNN with the audience filter IN SQL
   5. ── empty? ──────────────▶ controlled UNKNOWN. The model is never called.
   6. AssistantDataService     the asker's own records, chosen from what retrieval matched
   7. PromptBuilder            policy + who is asking + reachable pages + fenced knowledge
                               + their live records, fenced separately
   8. MistralLlmProvider       one completion, JSON mode
   9. ResponseValidator        parse, coerce, drop unknown fields, re-check navigation
  10. ConversationService      record the turn
  11. AiInteractionLogger      record what it was working from
        ▼
AssistantResponse              always HTTP 200, even for a decline
```

## The decisions that carry weight

### Authority comes from the database, every turn

The request body accepts `message`, `conversationId` and `currentPageId`. There is no field for a
role, a permission, a user id or a route, so a forged one is not rejected — it has nowhere to be
read from. `AiAssistantService.buildContext` re-resolves the user from the authenticated email on
every single turn, which is the pattern all 35 OneHR controllers already follow.

This also means **conversation history can never escalate anything**. Prior turns are re-injected as
plain transcript; authorisation is recomputed from scratch, so an earlier turn cannot establish a
permission a later turn relies on.

### Two role concepts, and they are not interchangeable

This is the subtlest thing in the codebase and the easiest to get wrong.

| | Derived from | Gates | Type |
|---|---|---|---|
| **Audience buckets** | *every* role the user holds, via `RoleUtils.audienceBuckets()` | knowledge retrieval | a **set** |
| **Shell role** | the single highest-priority role, via `RoleUtils.primaryRoleCode()` | navigation | a **single value** |

An HR Admin holds `{EMPLOYEE, HR}`. A Super Admin holds `{EMPLOYEE, ADMIN}`. Both contain
`EMPLOYEE`, so authorising navigation by bucket would offer a Super Admin the `my-team` page, which
is not in their sidebar. Navigation is keyed by `ShellRole`, which mirrors `nav.config.ts`'s
`toShellRole()` exactly — and `PageRegistryParityTest` fails the build if the two drift.

Retrieval is the opposite case: a Manager genuinely should see Employee-tagged knowledge, because
they genuinely hold the Employee role. That is why it filters on the set.

### The audience filter is in the SQL, not in Java

`PgVectorKnowledgeIndexRepository` applies the audience predicate inside the kNN query:

```sql
WHERE EXISTS (SELECT 1 FROM ai_knowledge_chunk_audience a
              WHERE a.chunk_id = c.id AND a.audience IN (…))
ORDER BY c.embedding <=> ?::vector LIMIT ?
```

Filtering after the fact would mean unauthorised chunks were fetched, ranked, and dropped only by a
line of Java somebody could later move. It would also silently degrade quality — the top-k would be
spent on rows the user cannot see.

**Audience semantics here are fail-closed and deliberately differ from `help_content_audience`:**
no audience rows means visible to **nobody**. The YAML schema validator rejects an untagged unit at
index time. Help & Guidance keeps its own "no rows means everyone" rule, applied at ingestion, since
that matches what `/help` already shows people.

### The vector column is not a JPA entity

`ai_knowledge_chunk` is reachable only through `JdbcTemplate` with parameterised native SQL. This is
not a style preference. Tests run against H2 with `ddl-auto: create-drop` and Flyway disabled; a JPA
entity carrying `vector(1024)` would make Hibernate emit that type against H2, and
`OneHrApplicationTests.contextLoads()` plus every other `@SpringBootTest` would fail.

`AiSchemaMappingTest` asserts no entity maps that table, and separately writes and reads back every
table that *is* mapped. That second half matters more than it looks: **Hibernate only warns when a
CREATE TABLE fails under `create-drop`** — it logs and carries on, leaving the table absent and the
build green. Twenty-one existing OneHR tables are in exactly that state today because they pin
`columnDefinition = "TIMESTAMPTZ"` or `"jsonb"`, which H2 does not know. Nobody noticed because no
test queries them. The assistant's entities therefore pin neither; Flyway still owns the real column
types.

### Retrieved content is data, never instruction

Knowledge goes into the prompt fenced and labelled:

```
<knowledge id="action.leave.apply" type="ACTION" module="leave" pageId="leave">
…
</knowledge>
```

`PromptBuilder.fence()` neutralises any `<knowledge` or `</knowledge` marker inside a title or body
before it is written, so a Help & Guidance article cannot close the fence early and have the rest of
itself read as prompt. That is the only way a knowledge unit could promote itself from content to
command, and Help content is typed into a form by a person — so it is a realistic path, not a
theoretical one.

The model is also shown only the pages the caller's own role can reach, which removes the most
likely cause of bad navigation before it can happen. `NavigationValidator` re-checks the result
independently anyway.

### Live records reuse the existing scoping, rather than re-implementing it

Every live figure comes from a service method that already takes the actor's email and resolves the
caller itself — `listMyBalances(actorEmail)`, `myClaims(actorEmail)`,
`getMyExceptions(actorEmail, from, to)`. **This feature contains no authorisation logic of its own**,
which is the single reason it is safe to have built: it walks the same code path the page walks, so
there is no second implementation that could drift out of agreement with the first.

Methods that take no actor at all, such as `LeaveService.listOrgLeave(from, to)`, would hand over an
entire organisation. `DataProviderSafetyTest` fails the build if a provider reaches one, and pins
the provider list to a reviewed set so a new one cannot appear without somebody answering "does this
read only the caller's own data?".

Live records are fenced in `<userdata>` tags rather than folded into `<knowledge>`, because the two
are different kinds of claim and the model must not treat them alike: knowledge says how OneHR
behaves for everyone, while this says what is true for one person right now. They are also the first
content in the system an ordinary employee can influence — an expense rejection reason is free text
typed by a manager — so they go through the same fence sanitiser.

See [live-data.md](live-data.md).

### Failure degrades; it does not throw

A provider outage is not the user's error. `AiAssistantService` catches its own failures and returns
a controlled `UNKNOWN` with **HTTP 200**. Anything that still escapes hits `AiExceptionHandler`,
a `@RestControllerAdvice` scoped with `assignableTypes` to the assistant controller alone, so it
cannot change how any existing OneHR endpoint behaves.

Its single hard rule: **every response carries a JSON body.** `authFetch.ts` treats an empty-bodied
403 on an authenticated `/api/` call as a dead session — it clears the auth store and hard-redirects
to the login page. An assistant permission error returning a bodyless 403 would sign the user out of
OneHR entirely, losing whatever they were doing, because they asked the chatbot the wrong question.

### The frontend is defence in depth, not the control

`Shell.tsx` resolves the active nav item with
`navItems.find(n => path.startsWith(n.path)) ?? navItems[0]` and renders `<Outlet/>` whenever that
item is not Phase 2. So a route a role has no nav item for falls back to `dashboard` — which is
enabled — and **the real page still renders**. The frontend has never been a navigation safety net.

`lib/ai/pageTargets.ts` checks anyway, because the one place that turns model output into a route is
worth checking twice. The server remains authoritative.

## Components

| Package | Responsibility |
|---|---|
| `ai/contract/` | Provider-neutral shapes and SPIs. Nothing here knows about Mistral or pgvector. |
| `ai/index/` | `JdbcTemplate` vector I/O. The only code that touches `ai_knowledge_chunk`. |
| `ai/knowledge/` | Loading, validating, chunking and indexing from YAML and Help & Guidance. |
| `ai/navigation/` | The page registry and the only thing allowed to authorise a destination. |
| `ai/provider/mistral/` | The one provider adapter, on the JDK's `HttpClient`. |
| `ai/retrieval/` | Query embedding, kNN, boosts, dedupe, context budget. |
| `ai/prompt/` | System and user prompt assembly. |
| `ai/response/` | Model output validation and the controlled declines. |
| `ai/service/` | Orchestration, conversations, rate limiting. |
| `ai/data/` | The asker's own live records, read through existing actor-scoped services. |
| `ai/observability/` | The interaction log and feedback. |
| `ai/action/` | The inert extension point. See [action-execution.md](action-execution.md). |

## No new dependencies

The JDK's `java.net.http.HttpClient` (the pattern `EmailService` already uses), Jackson, snakeyaml
and `JdbcTemplate` — all already on the classpath. No WebClient, no WebFlux, no vector-store library.
