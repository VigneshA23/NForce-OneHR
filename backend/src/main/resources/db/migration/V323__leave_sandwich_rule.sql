-- LV-07 (ONEHR-893): leave ranges may cross weekly offs and holidays; only working days are counted, unless
-- the plan's sandwich rule counts the non-working days between leave days too.
--
-- leave_plan_type_rules gains, per leave type in a plan version:
--   sandwich_enabled          count weekly offs / holidays that fall between two leave days of the request.
--   sandwich_applies_to       HOLIDAYS, WEEK_OFFS or BOTH.
--   sandwich_min_days         only when the leave covers at least this many working days (null = always).
--   sandwich_across_requests  also count the non-working days between this request and an adjacent request
--                             of the same type (e.g. Friday and Monday applied for separately).
-- leave_requests.day_counts: the dates a request was charged for, as
--   {"working": {"2026-12-04": 1, ...}, "sandwiched": {"2026-12-05": 1, ...}}
--   so balances split per leave year and carry-forward cut-offs use the real dates. Null for requests made
--   before V323, whose ranges held only working days (every date in the range counted).
--
-- Nothing changes on release day: sandwich is off everywhere, and every existing request keeps its count.
-- Rollback: drop the five columns. Requests saved since then may span non-working days; their total_days
-- stays correct but older code would split a cross-year one by calendar days.

ALTER TABLE leave_plan_type_rules
    ADD COLUMN sandwich_enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN sandwich_applies_to      VARCHAR(10)  NOT NULL DEFAULT 'BOTH'
        CHECK (sandwich_applies_to IN ('HOLIDAYS', 'WEEK_OFFS', 'BOTH')),
    ADD COLUMN sandwich_min_days        NUMERIC(5,2) CHECK (sandwich_min_days IS NULL OR sandwich_min_days > 0),
    ADD COLUMN sandwich_across_requests BOOLEAN      NOT NULL DEFAULT FALSE;

ALTER TABLE leave_requests
    ADD COLUMN day_counts JSONB;
