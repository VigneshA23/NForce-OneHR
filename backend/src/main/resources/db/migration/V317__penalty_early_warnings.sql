-- NForce OneHR - Flyway Migration V317
-- PN-03: early-warning notifications before a penalty applies.
--  (1) per-policy-version configuration (on/off + thresholds). Off by default so existing policies
--      do not start emailing employees on deploy.
--  (2) a de-duplication log: one row per employee / warning type / period / threshold, so the same
--      warning is never sent twice in the same period (the unique constraint is the guarantee).

ALTER TABLE penalization_policy_versions
    ADD COLUMN IF NOT EXISTS ew_enabled                  BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ew_late_arrival_enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS ew_missing_logs_enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS ew_shortage_enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    -- warn when this many (or fewer) exempt occurrences remain; 1 = "exempt limit minus 1"
    ADD COLUMN IF NOT EXISTS ew_exempt_remaining         INTEGER       NOT NULL DEFAULT 1,
    -- warn when the period's work-hours percentage is within this many points above a shortage tier
    ADD COLUMN IF NOT EXISTS ew_shortage_margin_percent  NUMERIC(5,2)  NOT NULL DEFAULT 10;

CREATE TABLE penalty_early_warnings (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    warning_type     VARCHAR(30)  NOT NULL CHECK (warning_type IN ('LATE_ARRIVAL', 'MISSING_LOGS', 'WORK_HOURS_SHORTAGE')),
    period_start     DATE         NOT NULL,
    threshold_key    VARCHAR(40)  NOT NULL,
    sent_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_penalty_early_warning UNIQUE (employee_user_id, warning_type, period_start, threshold_key)
);
