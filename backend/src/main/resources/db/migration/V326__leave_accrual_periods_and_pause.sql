-- LV-03 (ONEHR-889): accrual periods, credit timing and pausing, per leave type in a plan.
--
-- leave_plan_type_rules:
--   credit_frequency            gains QUARTERLY and HALF_YEARLY (the column widens to fit HALF_YEARLY).
--   credit_timing               START: a period's days are credited on its first day (as so far);
--                               END: once the period is over (the day after it ends; for a leaver, the day
--                               after the last working day).
--   accrual_pause_lop_over_days / accrual_pause_unpaid_over_days
--                               a period's credit is withheld when the employee had more than this many days
--                               of loss of pay (payroll's LOP figure) / approved unpaid leave in the period it
--                               depends on: the period itself when credited at the END, the period before it
--                               when credited at the START (its own period hasn't happened yet). Null = never.
--
-- leave_accrual_credits.source gains PAUSED: a withheld month is recorded as a 0-day row, so the pause is
-- visible in the credit history and re-runs never credit it later.
--
-- Defaults (START, no pause) are exactly how crediting worked until now: nothing changes.
--
-- Rollback: delete the PAUSED rows, restore the two CHECKs (YEARLY/MONTHLY, ACCRUAL/MIGRATION) after setting
-- QUARTERLY / HALF_YEARLY rules back to MONTHLY, and drop the three columns.

ALTER TABLE leave_plan_type_rules ALTER COLUMN credit_frequency TYPE VARCHAR(12);
ALTER TABLE leave_plan_type_rules DROP CONSTRAINT IF EXISTS leave_plan_type_rules_credit_frequency_check;
ALTER TABLE leave_plan_type_rules ADD CONSTRAINT leave_plan_type_rules_credit_frequency_check
    CHECK (credit_frequency IN ('YEARLY', 'HALF_YEARLY', 'QUARTERLY', 'MONTHLY'));

ALTER TABLE leave_plan_type_rules
    ADD COLUMN credit_timing                  VARCHAR(5)   NOT NULL DEFAULT 'START' CHECK (credit_timing IN ('START', 'END')),
    ADD COLUMN accrual_pause_lop_over_days    NUMERIC(5,1) CHECK (accrual_pause_lop_over_days IS NULL OR accrual_pause_lop_over_days >= 0),
    ADD COLUMN accrual_pause_unpaid_over_days NUMERIC(5,1) CHECK (accrual_pause_unpaid_over_days IS NULL OR accrual_pause_unpaid_over_days >= 0);

ALTER TABLE leave_accrual_credits DROP CONSTRAINT IF EXISTS leave_accrual_credits_source_check;
ALTER TABLE leave_accrual_credits ADD CONSTRAINT leave_accrual_credits_source_check
    CHECK (source IN ('ACCRUAL', 'MIGRATION', 'PAUSED'));
