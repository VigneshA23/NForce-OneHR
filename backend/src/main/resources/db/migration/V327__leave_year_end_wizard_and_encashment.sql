-- LV-05 (ONEHR-891): leave year-end as a wizard (Preview → Adjust → Confirm, rollback within N days), with
-- carry-forward caps by days and/or percent, a fixed-date expiry, and encashment paid through payroll.
--
-- leave_plan_type_rules (regular paid types):
--   carry_forward_max_percent         carry at most this % of the unused days (with max days: the lower of both).
--   carry_forward_expiry_month / _day carried days expire on this date of the next year (e.g. 3 / 31 = use by
--                                     31 March); alternative to carry_forward_expiry_months. Both null = no fixed date.
--   encash_enabled, encash_max_days   unused days beyond the carry-forward are encashed, up to the max (null = all).
--   encash_basis                      BASIC or BASIC_DA (monthly Basic, or Basic + DA, from the salary structure).
--   encash_divisor                    26 or 30: amount = days × monthly basis ÷ divisor.
-- Order at year-end: carry up to its cap → encash up to its cap → the rest lapses.
--
-- leave_year_end_runs / leave_year_end_run_lines: one run per preview; its lines (employee × type) hold the
-- closing balance, the suggested and the (possibly adjusted) carry / encash / lapse, and the encashment amount.
-- Only one PREVIEW and one CONFIRMED run per leave year.
--
-- leave_carry_forward_transfers gains encashed_days, run_id (the confirmed run that created it, null for
-- transfers made by the nightly job before LV-05) and adjusted (HR changed the figures: never topped up).
--
-- leave_encashments: a confirmed encashment waiting for payroll. The next payroll draft generated (or
-- regenerated) for the employee adds it as a "Leave Encashment" ad-hoc payment (status ADDED); a rollback
-- cancels it (and removes the payment while its run is still a draft).
--
-- Behaviour change: the nightly year-end job no longer closes the year by itself; HR confirms the wizard. It
-- still applies carried-forward expiries and top-ups.
--
-- Rollback: drop leave_encashments, leave_year_end_run_lines, leave_year_end_runs, the three transfer columns
-- and the seven rule columns.

ALTER TABLE leave_plan_type_rules
    ADD COLUMN carry_forward_max_percent  NUMERIC(5,2) CHECK (carry_forward_max_percent IS NULL OR carry_forward_max_percent BETWEEN 0 AND 100),
    ADD COLUMN carry_forward_expiry_month INTEGER      CHECK (carry_forward_expiry_month IS NULL OR carry_forward_expiry_month BETWEEN 1 AND 12),
    ADD COLUMN carry_forward_expiry_day   INTEGER      CHECK (carry_forward_expiry_day IS NULL OR carry_forward_expiry_day BETWEEN 1 AND 31),
    ADD COLUMN encash_enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN encash_max_days            NUMERIC(6,2) CHECK (encash_max_days IS NULL OR encash_max_days >= 0),
    ADD COLUMN encash_basis               VARCHAR(8)   NOT NULL DEFAULT 'BASIC' CHECK (encash_basis IN ('BASIC', 'BASIC_DA')),
    ADD COLUMN encash_divisor             INTEGER      NOT NULL DEFAULT 30 CHECK (encash_divisor IN (26, 30)),
    ADD CONSTRAINT chk_leave_plan_rule_cf_expiry_date
        CHECK ((carry_forward_expiry_month IS NULL) = (carry_forward_expiry_day IS NULL));

CREATE TABLE leave_year_end_runs (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    from_year       INTEGER     NOT NULL,
    status          VARCHAR(12) NOT NULL CHECK (status IN ('PREVIEW', 'CONFIRMED', 'ROLLED_BACK')),
    created_by      UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_by    UUID        REFERENCES users(id) ON DELETE SET NULL,
    confirmed_at    TIMESTAMPTZ,
    rolled_back_by  UUID        REFERENCES users(id) ON DELETE SET NULL,
    rolled_back_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_leave_year_end_run_preview ON leave_year_end_runs (from_year) WHERE status = 'PREVIEW';
CREATE UNIQUE INDEX uq_leave_year_end_run_confirmed ON leave_year_end_runs (from_year) WHERE status = 'CONFIRMED';

CREATE TABLE leave_year_end_run_lines (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id            UUID         NOT NULL REFERENCES leave_year_end_runs(id) ON DELETE CASCADE,
    employee_user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    leave_type_id     UUID         NOT NULL REFERENCES leave_types(id) ON DELETE CASCADE,
    closing_days      NUMERIC(6,2) NOT NULL,
    suggested_carry   NUMERIC(6,2) NOT NULL,
    suggested_encash  NUMERIC(6,2) NOT NULL,
    carry_days        NUMERIC(6,2) NOT NULL CHECK (carry_days >= 0),
    encash_days       NUMERIC(6,2) NOT NULL CHECK (encash_days >= 0),
    lapse_days        NUMERIC(6,2) NOT NULL CHECK (lapse_days >= 0),
    expires_on        DATE,
    encash_basis      VARCHAR(8),
    encash_divisor    INTEGER,
    monthly_base      NUMERIC(12,2),
    encash_amount     NUMERIC(12,2),
    note              TEXT,
    adjusted          BOOLEAN      NOT NULL DEFAULT FALSE,
    adjusted_by       UUID         REFERENCES users(id) ON DELETE SET NULL,
    adjusted_at       TIMESTAMPTZ,
    UNIQUE (run_id, employee_user_id, leave_type_id)
);
CREATE INDEX idx_leave_year_end_run_lines_run ON leave_year_end_run_lines (run_id);

ALTER TABLE leave_carry_forward_transfers
    ADD COLUMN encashed_days NUMERIC(6,2) NOT NULL DEFAULT 0,
    ADD COLUMN run_id        UUID REFERENCES leave_year_end_runs(id) ON DELETE SET NULL,
    ADD COLUMN adjusted      BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE leave_encashments (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id            UUID          NOT NULL REFERENCES leave_year_end_runs(id) ON DELETE CASCADE,
    employee_user_id  UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    leave_type_id     UUID          NOT NULL REFERENCES leave_types(id) ON DELETE CASCADE,
    from_year         INTEGER       NOT NULL,
    days              NUMERIC(6,2)  NOT NULL CHECK (days > 0),
    amount            NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    status            VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'ADDED', 'CANCELLED')),
    payroll_run_id    UUID,
    ad_hoc_payment_id UUID,
    created_by        UUID          REFERENCES users(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_leave_encashments_pending ON leave_encashments (employee_user_id) WHERE status = 'PENDING';
