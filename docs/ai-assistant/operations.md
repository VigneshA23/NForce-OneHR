# Operations

## Endpoints

| Method | Path | Guard |
|---|---|---|
| POST | `/api/ai-assistant/chat` | authenticated |
| GET | `/api/ai-assistant/conversations/{id}` | authenticated, owner-scoped |
| POST | `/api/ai-assistant/conversations/{id}/clear` | authenticated, owner-scoped |
| POST | `/api/ai-assistant/feedback` | authenticated |
| GET | `/api/ai-assistant/health` | authenticated |
| POST | `/api/ai-assistant/admin/reindex` | `hasRole('SUPER_ADMIN')` |

Owner-scoping is in the query, not in a check afterwards — `AiConversationRepository` has no
unscoped `findById`, so someone else's conversation id returns an empty transcript rather than a
403. `/health` is open to any authenticated user on purpose: it decides whether the launcher renders
at all, and gating it would make every employee's first interaction a 403.

## Re-indexing

```bash
curl -X POST https://<backend>/api/ai-assistant/admin/reindex \
     -H "Authorization: Bearer <super-admin-token>"
```

Returns `{ documents, chunks, removed, startedAt, finishedAt }`.

**Re-index after:** editing any `ai-knowledge/**.yaml`, publishing or unpublishing a Help & Guidance
article, or changing the embedding model (which also needs a migration — see
[configuration.md](configuration.md)).

It is a full rebuild, not incremental. Deletion is by `sourceRef` prefix per source, because an
upsert loop can only add or update: a unit deleted from a YAML file, or an article that was
unpublished, leaves nothing to upsert against and would otherwise stay retrievable forever. The
unpublished case is the one that actually matters — it would keep serving content somebody
deliberately withdrew.

Validation runs across every source together *before* anything is written, so a broken file cannot
leave the index half-rebuilt. Embedding width is checked first.

It is not run on startup or on a schedule. Startup indexing would make every deploy pay for a full
embedding run and would couple OneHR booting to a third-party API being reachable — a bad trade for
a system whose core job is HR administration.

## Health

```bash
curl https://<backend>/api/ai-assistant/health -H "Authorization: Bearer <token>"
```

```json
{ "enabled": true, "indexReady": true, "indexedChunks": 29, "lastIndexedAt": "…",
  "knowledgeSources": ["yaml", "help-content"], "llmProvider": "mistral",
  "embeddingProvider": "mistral-embed", "embeddingDimensions": 1024 }
```

`indexReady` is simply `chunks > 0`. The launcher renders only when `enabled && indexReady`.

## The interaction log

`ai_interaction_log` (V187) holds one row per turn. It answers the question a support ticket three
months later actually asks — not "what did it say", which is in the conversation, but **"what was it
working from when it said it"**.

```sql
-- Why did it answer that? Read back what the model was given.
SELECT created_at, response_type, confidence, navigation_page_id,
       retrieved_knowledge_ids, top_score, error_code
FROM ai_interaction_log
WHERE user_id = '…' ORDER BY created_at DESC LIMIT 20;

-- Where is it failing?
SELECT error_code, count(*) FROM ai_interaction_log
WHERE created_at > now() - interval '7 days' GROUP BY 1 ORDER BY 2 DESC;

-- Every thumbs-down, with what was retrieved for it.
SELECT created_at, feedback_comment, retrieved_knowledge_ids, top_score
FROM ai_interaction_log WHERE feedback_rating = 'DOWN' ORDER BY created_at DESC;
```

`error_code` values: `DISABLED`, `EMPTY_MESSAGE`, `MESSAGE_TOO_LONG`, `RATE_LIMITED`,
`NO_KNOWLEDGE`, `RETRIEVAL_UNAVAILABLE`, `PROVIDER_UNAVAILABLE`, `UNKNOWN_ANSWER`.

`UNKNOWN_ANSWER` is the interesting one: retrieval found something and the model was paid for, yet
the user got nothing. A spike there is a prompt or knowledge problem.

**The table holds no message text.** `AiInteractionLogger.Turn` carries `questionChars`, an int —
there is no field the question could be put into by a later change that seemed reasonable at the
time. The text lives in `ai_conversation_message` and is not duplicated, because copying employee
free text into a second table doubles the retention surface for no analytical gain.

Logging never breaks a turn: every write is wrapped and swallowed. The user came here for help with
their leave request; the metrics are for us.

## Retention and PII

| Table | Contains | Sensitivity |
|---|---|---|
| `ai_conversation_message` | questions and answers as written | **employee free text, and now live figures** — an answer can contain a leave balance or a claim amount |
| `ai_interaction_log` | metrics, knowledge ids, `feedback_comment` | links a user to a timestamped activity trail |

Since live data was added, a stored answer can contain the asker's own balances, claim statuses and
amounts. That raises the sensitivity of `ai_conversation_message` specifically, and makes a
retention policy more pressing than it was when answers held only documentation.

**Nothing purges either table today.** When a policy is agreed, the intended shape is: delete
`ai_conversation_message` rows beyond the window first, then `ai_interaction_log` rows beyond a
longer one — keeping the aggregate quality history after the personal detail has gone. The FK is
`ON DELETE SET NULL` specifically so purging conversations does not erase the metrics.

`com.nforce.onehr` logs at **DEBUG in every environment**, so neither message content nor feedback
comments are ever written to the application logger. API keys are never logged anywhere.

## Troubleshooting

**The launcher does not appear.** Check `/health`. `enabled: false` means `AI_ASSISTANT_ENABLED` is
not set. `indexReady: false` means nobody has re-indexed. If the health call itself fails the
launcher hides silently by design.

**Everything comes back "I don't have enough information".** Retrieval is returning nothing. Check
`indexedChunks > 0`, then check the audience tags on the units you expect — a unit tagged `[HR]`
is invisible to an Employee *by design*. `SELECT error_code, count(*)` will show `NO_KNOWLEDGE`
dominating.

**"The assistant is temporarily unavailable."** A provider failure, degraded to a controlled
response with HTTP 200. Look for `PROVIDER_UNAVAILABLE` or `RETRIEVAL_UNAVAILABLE` in the log and
check the API key and model availability — a model your subscription cannot serve returns 403
`tier_not_allowed`, and an exhausted quota returns a persistent 429.

**It answers general-knowledge questions.** `min-score` has drifted too low. Unrelated text scores
~0.594 against this knowledge base with real `mistral-embed`; the floor is 0.60 for exactly this
reason. Run `emp.scope.capital` from the evaluation set.

**It offers a page the user cannot open.** Should be impossible — `NavigationValidator` authorises
against `ShellRole` server-side and `pageTargets.ts` checks again. If it happens, `PageRegistryParityTest`
has probably been skipped or the registry has drifted from `nav.config.ts`.

**`contextLoads()` fails with a circular reference through `LeaveService`.** Almost certainly a
stale incremental build, not a real cycle. `LeaveService` breaks that cycle with `@Lazy` on its
`ExceptionService` field, which only reaches the generated constructor because `backend/lombok.config`
sets `lombok.copyableAnnotations += org.springframework.context.annotation.Lazy`. An incremental
recompile can drop it, and the cycle comes back looking like a genuine bug in whatever you just
changed. Confirm and fix with:

```bash
javap -v -p target/classes/com/nforce/onehr/service/LeaveService.class | grep -c RuntimeVisibleParameterAnnotations
mvn clean compile      # if that printed 0
```

This is a repo-wide trap, not an assistant one — but it surfaces as every `@SpringBootTest` failing
at once, which looks exactly like the vector column reaching Hibernate, so it is worth ruling out
first.

**Flyway refuses to start: duplicate version.** The shared dev Neon DB is migrated by many branches
and renumbering is routine here. Check `max(version)` in `flyway_schema_history` and renumber
`V185`–`V187` to follow it, keeping their relative order.

## Running the evaluation set

Free, runs in the normal suite, validates the fixture against the real registry and knowledge base:

```bash
cd backend && mvn test -Dtest=AiEvaluationSetTest -DfailIfNoSpecifiedTests=false
```

Live, costs real API calls, prints a scorecard, **skipped unless credentials are supplied**:

```bash
AI_EVAL_BASE_URL=http://localhost:8081 \
AI_EVAL_EMPLOYEE=employee@example.com:password \
AI_EVAL_MANAGER=manager@example.com:password \
AI_EVAL_HR_ADMIN=hr@example.com:password \
AI_EVAL_SUPER_ADMIN=admin@example.com:password \
  mvn test -Dtest=AiEvaluationHarness -DfailIfNoSpecifiedTests=false
```

It goes over HTTP and logs in with real credentials rather than constructing a context, so JWT
auth, the role lookup, the audience filter and navigation authorisation are all exercised exactly as
a browser would. It **reports** rather than asserts: answers are model output, and the same question
can pass then fail without a line of code changing. A build that fails for reasons nobody can fix is
a build people learn to ignore.
