# Configuration

Everything lives under `app.ai` in `backend/src/main/resources/application.yml`. No other key in
that file changed.

## Environment variables

| Variable | Default | Notes |
|---|---|---|
| `AI_ASSISTANT_ENABLED` | `false` | Master switch. Off by default so OneHR boots normally in an environment with no AI credentials at all. |
| `MISTRAL_API_KEY` | *(none)* | **Required when enabled.** No default, matching `JWT_SECRET` and `RESEND_API_KEY` — an unset key fails loudly rather than silently calling an unauthenticated API. |
| `AI_PROVIDER` | `mistral` | Reported by `/health`. Only `mistral` is implemented. |
| `MISTRAL_CHAT_MODEL` | `ministral-8b-latest` | See below. |
| `MISTRAL_EMBED_MODEL` | `mistral-embed` | **Do not change without a migration.** See below. |

Set the key in Railway's config for the backend. Never in a committed file. Local development uses
`application-local.yml`, which is gitignored.

## Tuned values, and why they are what they are

```yaml
app:
  ai:
    mistral:
      timeout-seconds: 30
    retrieval:
      top-k: 8
      min-score: 0.60
      max-context-chars: 12000
    limits:
      max-message-chars: 1000
      max-requests-per-user-per-hour: 60
      max-history-turns: 6
```

### `chat-model: ministral-8b-latest`

Not a preference — it is what the current subscription can actually serve, verified against the live
API rather than from the docs:

| Model | Result |
|---|---|
| `mistral-large-latest` | HTTP 403 `tier_not_allowed` |
| `mistral-small-latest` | persistent HTTP 429 |
| `mistral-medium-latest` | persistent HTTP 429 |
| `ministral-8b-latest` | works |

It handles grounded question-answering over retrieved text perfectly well; the hard part of this
system is retrieval, not generation. Raise it via `MISTRAL_CHAT_MODEL` once the subscription allows
— no code change needed.

### `min-score: 0.60`

The original plan said 0.35. That was badly wrong, and only measurement showed it. Against the real
knowledge base with real `mistral-embed` vectors:

| Question | Top score |
|---|---|
| Direct hit ("how do I apply for leave") | ~0.90 |
| Informal phrasing of a real question | 0.68 – 0.79 |
| Weak but genuine question | ~0.62 |
| **"What is the capital of France?"** | **0.594** |

Cosine similarity over `mistral-embed` simply does not go near zero for unrelated text. A 0.35 floor
would have filtered nothing at all and the assistant would have answered trivia from whatever
happened to rank first. `emp.scope.capital` in the evaluation set is the canary: if it ever starts
producing an answer, this threshold has drifted.

### `embed-model: mistral-embed` — coupled to the schema

`mistral-embed` produces **1024-dimension** vectors, which is the width of `ai_knowledge_chunk.embedding`
in `V185`. Changing the embedding model means changing that column width **and** fully re-indexing.

`KnowledgeIndexingService` refuses to start a re-index if the provider's dimensionality does not
match `KnowledgeIndexRepository.EMBEDDING_DIMENSIONS`. Discovering a mismatch after writing several
hundred rows would leave an index full of vectors that are individually valid and collectively
meaningless — every similarity score would be noise, and nothing would look broken.

### `max-requests-per-user-per-hour: 60`

Every message costs a real embedding call *and* a real completion call. `AiRateLimiter` is in-memory
and per-instance, following the same pragmatic choice already made for `SseTicketService` and
`ForceLogoutBroadcaster`. On a multi-instance deployment the effective limit is this number times the
instance count — acceptable, because **it is a cost guard, not a security control**, and not worth
introducing Redis for.

### `max-history-turns: 6`

Bounded so a long conversation cannot grow the prompt without limit. History is plain transcript and
never affects authorisation.

## Swapping the provider

`LlmProvider` and `EmbeddingProvider` are separate interfaces in `ai/contract/`, and nothing outside
`ai/provider/mistral/` mentions Mistral. To add another:

1. Implement both interfaces in `ai/provider/<vendor>/`, using `java.net.http.HttpClient` — the
   house pattern, no new dependency.
2. `EmbeddingProvider.dimensions()` must return the model's real width.
3. If that width is not 1024, add a migration altering `ai_knowledge_chunk.embedding` and update
   `KnowledgeIndexRepository.EMBEDDING_DIMENSIONS`.
4. Re-index from scratch. **Vectors from two different embedding models are not comparable**, so a
   partial re-index produces an index that returns confident nonsense.

## Frontend

No environment variable. The launcher calls `GET /api/ai-assistant/health` and renders nothing
unless the response reports `enabled && indexReady`. If the health call fails it also renders
nothing — the failure is silent by design, since a user who never knew the feature existed has lost
nothing.

`MAX_MESSAGE_CHARS` in `assistantState.ts` mirrors `max-message-chars` so the user sees the limit
while typing. The server enforces it regardless, and refuses an over-long message *before* it costs
an embedding call.
