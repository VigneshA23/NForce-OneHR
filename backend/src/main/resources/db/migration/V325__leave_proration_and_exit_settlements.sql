-- LV-04 (ONEHR-890): how a leave plan prorates a part-year entitlement, and the leave position of an employee
-- who leaves.
--
-- leave_plans gains (per plan, not versioned):
--   proration_method      MONTHS (whole months; the joining / exit month counts per proration_cutoff_day) or
--                         CALENDAR_DAYS (the joining / exit month counts by the days served in it).
--   proration_cutoff_day  MONTHS only: joining on or before this day counts the joining month; leaving on or
--                         after it counts the exit month. Null = those months always count (the behaviour so far).
--   proration_rounding    NEAREST_HALF, DOWN_HALF, UP_HALF or NONE: applied to a part-year all-at-once credit
--                         and to a partly-served month of monthly crediting.
-- The defaults (MONTHS, no cut-off, NEAREST_HALF) are exactly how crediting worked until now: nothing changes.
--
-- leave_exit_settlements: per employee with a last working day, per regular paid type of that leave year, the
-- entitlement recomputed up to the last working day, what was used, and the result: days that can be encashed
-- or days taken beyond the entitlement, to recover (payroll's exit / F&F reads it). Recomputed nightly while
-- recent, and on demand.
--
-- Rollback: drop the table and the three columns.

ALTER TABLE leave_plans
    ADD COLUMN proration_method     VARCHAR(14) NOT NULL DEFAULT 'MONTHS'
        CHECK (proration_method IN ('MONTHS', 'CALENDAR_DAYS')),
    ADD COLUMN proration_cutoff_day INTEGER CHECK (proration_cutoff_day IS NULL OR proration_cutoff_day BETWEEN 1 AND 31),
    ADD COLUMN proration_rounding   VARCHAR(12) NOT NULL DEFAULT 'NEAREST_HALF'
        CHECK (proration_rounding IN ('NEAREST_HALF', 'DOWN_HALF', 'UP_HALF', 'NONE'));

CREATE TABLE leave_exit_settlements (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id      UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    leave_type_id         UUID          NOT NULL REFERENCES leave_types(id) ON DELETE CASCADE,
    year                  INTEGER       NOT NULL,
    last_working_day      DATE          NOT NULL,
    entitled_days         NUMERIC(7,2)  NOT NULL,
    carried_forward_days  NUMERIC(7,2)  NOT NULL DEFAULT 0,
    used_days             NUMERIC(7,2)  NOT NULL,
    encashable_days       NUMERIC(7,2)  NOT NULL DEFAULT 0,
    recovery_days         NUMERIC(7,2)  NOT NULL DEFAULT 0,
    computed_at           TIMESTAMP     NOT NULL DEFAULT now(),
    UNIQUE (employee_user_id, leave_type_id, year)
);
CREATE INDEX idx_leave_exit_settlements_lwd ON leave_exit_settlements (last_working_day);
