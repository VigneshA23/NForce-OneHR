-- Persisted, Super-Admin-editable AI Assistant billing estimate. Singleton row, same
-- UNIQUE/CHECK(singleton) enforcement pattern as ai_rate_limit_settings (V192).
--
-- OneHR has no access to Mistral's actual billing (there is no billing API being integrated
-- against — the Mistral Admin Console, linked from the API Usage page, remains the source of
-- truth for real invoiced cost). This table only lets a Super Admin configure the per-million-
-- token prices their org is actually paying, so ai_interaction_log's already-tracked prompt/
-- completion token counts can be turned into a same-ballpark monthly estimate and a budget
-- progress bar inside OneHR itself, without OneHR ever guessing at Mistral's live price list.
--
-- Seeded with placeholder values only - NOT a claim about Mistral's actual current pricing,
-- which changes over time and varies by model. A Super Admin is expected to set these to match
-- their real plan the first time they open the page.
CREATE TABLE ai_billing_settings (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton                       BOOLEAN NOT NULL DEFAULT TRUE,
    monthly_budget_usd              NUMERIC(10,2) NOT NULL DEFAULT 50.00,
    prompt_cost_per_million_usd     NUMERIC(10,4) NOT NULL DEFAULT 0.10,
    completion_cost_per_million_usd NUMERIC(10,4) NOT NULL DEFAULT 0.10,
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_billing_singleton CHECK (singleton),
    CONSTRAINT uq_ai_billing_singleton UNIQUE (singleton),
    CONSTRAINT chk_ai_billing_budget CHECK (monthly_budget_usd >= 0),
    CONSTRAINT chk_ai_billing_prompt_cost CHECK (prompt_cost_per_million_usd >= 0),
    CONSTRAINT chk_ai_billing_completion_cost CHECK (completion_cost_per_million_usd >= 0)
);

INSERT INTO ai_billing_settings (monthly_budget_usd, prompt_cost_per_million_usd, completion_cost_per_million_usd)
VALUES (50.00, 0.10, 0.10)
ON CONFLICT (singleton) DO NOTHING;
