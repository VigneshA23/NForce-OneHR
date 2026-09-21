# Authoring knowledge

The assistant knows exactly what is in `backend/src/main/resources/ai-knowledge/`. Nothing else.
If it gives a wrong answer, the fix is almost always a YAML edit, not a code change.

## Why YAML in the repository

Knowledge describes how OneHR actually behaves, so it belongs beside the code it describes. It
reviews as a diff, ships with the change that made it true, and cannot drift into a state where
production says one thing and the assistant says another without someone seeing it in a pull
request.

The alternative — DB tables plus an admin UI — moves knowledge outside review, which is precisely
the wrong trade for content whose only job is to be correct. `KnowledgeSource` is an interface, so a
DB-backed authoring surface can be added later as a third source without retrieval, chunking or
indexing knowing anything changed.

## Layout

```
ai-knowledge/
  foundations/onehr-overview.yaml     what OneHR is, global rules, the "Access denied" table
  roles/role-definitions.yaml         what each role can do, who approves what
  modules/overview.yaml               one unit per module, shallow
  actions/leave.yaml                  deep: steps, validations, outcomes
  actions/attendance.yaml
  workflows/requests-and-approvals.yaml
  pages/registry.yaml                 NOT knowledge — see below
```

`pages/registry.yaml` is configuration for navigation and is excluded from knowledge loading.

**Coverage is deliberately uneven.** Attendance, Regularization, WFH, Overtime, Leave, Approvals,
My Requests, Help Desk, Help & Guidance, Profile and Dashboard are deep, at action level. Everything
else has a module overview. A shallow answer that points at the right page beats a detailed answer
that is out of date.

## The schema

```yaml
knowledge:
  - knowledgeId: action.leave.apply     # unique across the whole base; stable
    type: ACTION                        # FOUNDATION ROLE MODULE PAGE ACTION WORKFLOW ERROR FAQ TERM
    module: leave
    pageId: leave                       # must exist in pages/registry.yaml
    actionId: leave.apply               # optional
    workflowId:                         # optional
    version: 1                          # bump when the meaning changes
    audience: [EMPLOYEE, MANAGER, HR, ADMIN]
    title: Applying for leave
    synonyms:
      - book time off
      - I want a day off
    body: |
      …
```

### `audience` is fail-closed

No audience rows in the index means readable by **nobody**. An untagged unit is a build failure with
a filename, not a unit that quietly becomes visible to everyone. Tag the narrowest set that is
correct: `module.onboarding` is `[HR]` only, and the evaluation set proves an Employee asking the
same question retrieves nothing.

(Help & Guidance ingestion applies the opposite default — no tags means everyone — because that is
what `/help` already shows people. Being stricter than the page the article appears on would hide
articles users can plainly read.)

### `synonyms` are not decorative

They are folded into the text that gets embedded, because retrieval is semantic: *"my hours are
wrong for tuesday"* only finds the regularization unit if that phrasing is part of what was
embedded. Write the words a confused person actually types, not the words the feature is called.
Nobody who needs regularization knows the word "regularization".

### `body` is what the model sees

Write it as prose and numbered steps. Include the rules that will stop a submission — those are what
people are really asking about. State what does **not** exist as plainly as what does.

One chunk per authored unit, never a fixed token window: splitting on a character count would cut an
action's preconditions away from its steps, and retrieving half of that is worse than retrieving
none, because the model would answer confidently from an instruction set with the caveats removed.
Over ~4000 characters a unit is split on paragraph boundaries as a backstop — treat that as a signal
to split it by hand into two properly-titled units.

## Validation

`KnowledgeSchemaValidator` runs across every source before anything is written, and collects *all*
problems rather than throwing on the first. A re-index fails on:

- an unknown `pageId` — the most damaging failure this system has. The model reads "go to X",
  proposes X, `NavigationValidator` correctly refuses it, and the user gets an answer describing a
  destination with no way to reach it.
- an empty `audience`
- a duplicate `knowledgeId` — duplicates silently overwrite each other at upsert time
- a body under 40 characters — it still embeds to a valid vector, so it can win a retrieval slot and
  contribute nothing, crowding out the chunk that would have answered

YAML fails loudly because it goes through review. **Help & Guidance content does not** — a one-line
FAQ typed into a form must not be able to take down the entire re-index and with it every piece of
authored knowledge. Unusable articles are skipped, counted and logged.

## Adding a page

`pages/registry.yaml` is the only source of navigable targets. Routes are role-polymorphic, so
entries carry per-role variants:

```yaml
  - pageId: requests
    module: requests
    variants:
      - roles: [EMPLOYEE, MANAGER]
        label: "My Requests"
        route: /requests
        description: "Track your own Leave, Regularization, WFH and Overtime requests."
      - roles: [HR_ADMIN, SUPER_ADMIN]
        label: "HR Service Requests"
        route: /requests
        description: "The Help Desk ticket queue for HR."
```

`/requests` renders `MyRequestsPage` or `HelpDeskAdminPage` depending on who is looking, and the
sidebar names it differently. Offering "Open My Requests" to an HR Admin would describe a page they
are not about to get.

Mark a Phase 2 module with `placeholder: true`. `NavigationValidator` never emits a placeholder as a
target, but the model is still told it exists so it can say "that is on the roadmap" — the user can
see it in their own sidebar, so "that does not exist" would be visibly wrong.

`PageRegistryParityTest` parses `frontend/src/lib/nav.config.ts` and fails the build if the registry
and the real sidebar disagree on keys, labels or which roles have what. No backend code imports that
file, so nothing else would ever notice them diverging.

## Help & Guidance

Published articles are ingested automatically by `HelpContentKnowledgeSource` — an HR Admin
publishes an FAQ and the assistant can answer from it after the next re-index, with no code change.

Reading goes through `HelpContentSpecifications.publishedAndActive()`, so draft, pending, approved,
unpublished and archived rows are *structurally unable* to reach the index. Attachments are **not**
parsed: that would need a PDF/Office parsing dependency for marginal gain.

Re-indexing is explicit in v1. There is no listener on `HelpContentService`, so publishing behaves
exactly as it did before this feature existed — the cost is that a new article is not retrievable
until someone re-indexes.

## After editing

```bash
cd backend && mvn test -Dtest='AiEvaluationSetTest,PageRegistryParityTest'   # free, no API calls
```

Then re-index (see [operations.md](operations.md)) and spot-check the questions your edit affects in
`backend/src/test/resources/ai-eval/questions.yaml`. If you fixed a wrong answer, **add a question
there with a `why`** — that is how the fix stops being re-broken.
