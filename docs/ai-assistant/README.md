# OneHR AI Assistant

An authenticated, read-only support assistant built into OneHR. It answers questions about how
OneHR works — features, actions, workflows, roles, errors — and can offer to take the user to a
page they are allowed to open. It answers from a curated knowledge base plus, where the question
calls for it, the signed-in user's own live records — both scoped to what that user is allowed to
see.

## What it deliberately is not

- **It cannot change anything.** There is no code path from the assistant into leave, attendance,
  approvals or any other domain service. Its only writes are its own conversation and log rows. See
  [action-execution.md](action-execution.md) — the extension point for a future release exists and
  is structurally inert.
- **It is not a general chatbot.** With no OneHR knowledge retrieved for a question, the model is
  never called at all; the user gets a controlled decline pointing at Help & Guidance.
- **It does not know your company's policy.** Leave entitlements, notice periods and the like are
  configured per organisation and are not in the knowledge base. It explains where to look.
- **It reads only the asker's own records.** Live data comes from the same actor-scoped service
  methods the pages use, so an Employee sees themselves and a Manager sees their reports — there is
  no second authorisation path that could disagree with the first. See
  [live-data.md](live-data.md).

## What leaves your infrastructure

Static OneHR documentation, and — on questions that call for it — the asker's own live figures:
leave balances, expense claim statuses and amounts, attendance exceptions, pending approval counts.
That is real employee data going to a third-party API, which is a deliberate decision rather than an
accident of the design. [live-data.md](live-data.md) sets out exactly what can be sent, what cannot,
and the three limits that bound it.

## Where things live

| | |
|---|---|
| Backend | `backend/src/main/java/com/nforce/onehr/ai/` — one vertical slice, nothing outside it modified |
| Knowledge | `backend/src/main/resources/ai-knowledge/**.yaml` — version-controlled, reviewed as a diff |
| Migrations | `V185` (vector index), `V186` (conversations), `V187` (interaction log) |
| Frontend | `frontend/src/components/aiAssistant/`, `frontend/src/api/aiAssistant.ts`, `frontend/src/lib/ai/pageTargets.ts` |
| Evaluation | `backend/src/test/resources/ai-eval/questions.yaml` |

Exactly **two** existing frontend files are touched, both additively: `Shell.tsx` mounts the
launcher as its last child, and `index.css` gains the mobile panel rules.

## Documents

| | |
|---|---|
| [architecture.md](architecture.md) | How one turn works, and the security decisions inside it |
| [configuration.md](configuration.md) | Environment variables, enabling it, swapping the provider |
| [knowledge-authoring.md](knowledge-authoring.md) | Writing knowledge, the YAML schema, adding a page |
| [operations.md](operations.md) | Re-indexing, health, the interaction log, retention, troubleshooting |
| [live-data.md](live-data.md) | How the asker's own records reach an answer, and what bounds it |
| [action-execution.md](action-execution.md) | Why actions cannot run, and how to enable one safely later |

## The 60-second version

1. Set `MISTRAL_API_KEY` and `AI_ASSISTANT_ENABLED=true`.
2. Sign in as a Super Admin and `POST /api/ai-assistant/admin/reindex`.
3. The launcher appears for every user once `GET /api/ai-assistant/health` reports
   `enabled && indexReady`. Until then it renders nothing — a button that always says "I don't
   know" is worse than no button.
