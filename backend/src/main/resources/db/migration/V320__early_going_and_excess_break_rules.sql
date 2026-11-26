-- NForce OneHR - Flyway Migration V320
-- PN-05: two new penalization rules, versioned with the policy like every other section.
--  Early going : leaving before the shift's scheduled end. Same shape as Late Arrival
--                (grace minutes, exempt count per period, deduction per N occurrences).
--  Excess break: total break time in a day above a limit. Max break minutes per day,
--                exempt count per period, deduction.
-- Both are off by default so existing policies keep behaving exactly as before.

ALTER TABLE penalization_policy_versions
    ADD COLUMN IF NOT EXISTS early_going_enabled              BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS eg_grace_period_minutes          INTEGER,
    ADD COLUMN IF NOT EXISTS eg_exempt_count                  INTEGER,
    ADD COLUMN IF NOT EXISTS eg_exempt_period                 VARCHAR(10),
    ADD COLUMN IF NOT EXISTS eg_deduction_days                NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS eg_deduction_per_shifts          INTEGER,
    ADD COLUMN IF NOT EXISTS excess_break_enabled             BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS eb_max_break_minutes             INTEGER,
    ADD COLUMN IF NOT EXISTS eb_exempt_count                  INTEGER,
    ADD COLUMN IF NOT EXISTS eb_exempt_period                 VARCHAR(10),
    ADD COLUMN IF NOT EXISTS eb_deduction_days                NUMERIC(5,2);
