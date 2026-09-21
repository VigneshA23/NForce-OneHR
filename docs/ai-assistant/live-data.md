# Live data

The assistant can answer "how many leave days do I have left" with a number rather than directions
to a page. This is how, and what bounds it.

## What it can read

Seven providers in `ai/data/`, each calling exactly one existing service method:

| Provider | Reads | Who |
|---|---|---|
| `leave.balances` | `LeaveService.listMyBalances` | everyone |
| `leave.my-requests` | `LeaveService.listMyRequests` | everyone |
| `leave.pending-approvals` | `LeaveService.listPendingApprovals` | Manager, HR, Admin |
| `expense.my-claims` | `ExpenseService.myClaims` | everyone |
| `expense.pending-approvals` | `ExpenseService.pendingForManager` | Manager, HR, Admin |
| `attendance.today` | `AttendanceService.getToday` | everyone |
| `attendance.my-exceptions` | `AttendanceService.getMyExceptions` | everyone |

## Why this is safe to have built

**Every one of those methods already takes the actor's email and scopes the result itself.** The
assistant passes the email it holds from the JWT, and the service does exactly what it does for the
page. This feature therefore writes **no authorisation logic at all** — there is no second
implementation to drift out of agreement with the first, which is the usual way a data-access
feature starts leaking.

A method that takes no actor — `LeaveService.listOrgLeave(from, to)` is the clearest example — would
return an entire organisation's leave, and adding one would look like an ordinary one-line change.
`DataProviderSafetyTest` fails the build if a provider reaches one.

## The three limits

**1. Nothing is read unless the question is about it.** Providers are selected from the modules
retrieval already matched, so asking about leave never touches `ExpenseService`. An unrelated
question causes no query and puts no personal data in a prompt. The page the user is on contributes
too, since "what's the status of this?" on Assets & Expenses is about expenses — that hint is
validated against the page registry before it gets here, so it cannot widen anything.

**2. At most three providers per turn**, ranked by retrieval score. This is a ceiling on how much of
one person's record a single question can send to a third party. Ranking matters: capping in bean
order once let three loosely related providers crowd out the one the question was actually about,
which is both a worse answer and three needless reads.

**3. A provider returning nothing contributes nothing.** No claims means no section, not a section
saying "no claims". Most of what the assistant sees is absence.

## What is deliberately excluded

- **Leave reasons.** The most personal field on a leave row, and it adds nothing to an answer about
  status. Excluded even though the row is the asker's own.
- **Anyone else's records.** Manager-facing providers return the reports' names and request details
  because that is the approval queue's whole purpose, but there is no provider that reads a person
  the caller has no relationship with.
- **Writes.** A provider holds a service that *can* mutate — `LeaveService` can approve leave — so
  the discipline is that it calls one read method and returns text. `DataProviderSafetyTest` fails
  if a provider declares a method whose name suggests a write.

## What this changed about privacy

Before this, only static OneHR documentation left your infrastructure. Now, on questions that call
for it, real employee figures go to the model's API: balances, claim amounts and statuses,
attendance exceptions, pending counts, and the names of reports in an approval queue.

That was a deliberate decision, not a side effect. Two consequences worth holding onto:

- **`ai_conversation_message` is more sensitive than it was.** A stored answer can now contain a
  balance or a claim amount, which makes the retention policy in [operations.md](operations.md)
  more pressing than when answers held only documentation.
- **`ai_interaction_log` still holds no message text**, by design. It records *which* providers ran,
  never what they returned — so quality can be reviewed without reading anybody's figures.

## Prompt injection

This is the first content in the system an ordinary user can put in front of the model. An expense
rejection reason is free text typed by a manager. It is fenced in `<userdata>` tags, the fence
markers inside it are neutralised, and the standing policy declares both `<knowledge>` and
`<userdata>` to be data rather than instructions.

## Adding a provider

1. Implement `AssistantDataProvider` in `ai/data/`, calling **one** actor-scoped read method.
2. Declare `audiences()` and `modules()` — an empty set in either would run it for everyone, or on
   every question.
3. Add its class name to `DataProviderSafetyTest.noProviderAppearsWithoutReview`. That edit is the
   review step: it forces somebody to answer whether the new read is genuinely caller-scoped.
4. Add a question to `ai-eval/questions.yaml` covering both the case with data and the case without.

## Failure behaviour

A provider that throws costs its figure, not the turn. The answer falls back to the static
explanation — which is what the assistant returned before this existed, and is still useful.
Turning a live-data failure into an error would trade a good answer for no answer.
