-- Closes the gap between the API Usage dashboard's "Total Requests"/"Total Tokens" and what
-- Mistral's own Admin Console shows. Root cause: ai_interaction_log recorded one row per assistant
-- TURN, but a turn costs at least two real Mistral requests (an embedding call for retrieval, then
-- a chat completion call) and the embedding call's attempts/tokens were never captured at all.
--
-- api_call_attempts is the actual count of real Mistral HTTP requests this turn cost (embedding
-- attempts + completion attempts, including any transport-level retries) - AiUsageStatsService now
-- sums this instead of counting rows. embedding_prompt_tokens is the embedding call's own token
-- usage, folded into the "Total Tokens" figure and the billing estimate alongside the existing
-- prompt_tokens/completion_tokens (which remain the chat completion call's own usage, unchanged).
--
-- Historical rows predate this instrumentation and default to 1/NULL - they undercounted before
-- this migration and still will for their own history; only turns logged after this ships get an
-- accurate api_call_attempts.
ALTER TABLE ai_interaction_log
    ADD COLUMN IF NOT EXISTS embedding_prompt_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS api_call_attempts       INTEGER NOT NULL DEFAULT 1;

-- Knowledge reindexing (POST /admin/reindex) also calls Mistral's embeddings endpoint directly,
-- in batches, with no user turn to attach the cost to - it remains genuinely outside this table by
-- design (see KnowledgeIndexingService) and is called out in the API Usage page's own disclaimer.

-- ai_billing_settings gains a third per-million-token price so the embedding tokens above can be
-- priced into the monthly estimate the same way prompt/completion tokens already are.
ALTER TABLE ai_billing_settings
    ADD COLUMN IF NOT EXISTS embedding_cost_per_million_usd NUMERIC(10,4) NOT NULL DEFAULT 0.02;
