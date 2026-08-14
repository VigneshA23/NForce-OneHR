-- NForce OneHR — Flyway Migration V106
-- Penalization Policy Phase 1 enhancements (gap-analysis against the full requirements doc):
--   1. Links penalization_policy_versions to penalisation_policies (V95) so a policy version
--      belongs to a specific, named, assignable policy instead of one implicit org-wide document
--      — the foundation for multiple named policies (Policy List is a later phase; this migration
--      only lays the schema/FK groundwork so ConfiguredAttendancePolicyEngine can scope by the
--      employee's actually-assigned policy instead of "whichever version is effective anywhere").
--   2. Adds Basic Information fields (deduction method, leave priority order, buffer period,
--      notice-period override) that apply to the policy document as a whole, not one section.
--   3. Widens la_exempt_period/ml_exempt_period to allow WEEK in addition to MONTH.
--   4. Adds notice-period data to employees (none existed anywhere in the schema).
--   5. Adds deduction-outcome columns to attendance_penalties so a penalty's actual leave/LoP
--      split is persisted and traceable, not just the pre-deduction day count.
-- All additive/nullable-with-defaults — no existing row becomes invalid, no existing behavior
-- changes for the single policy every employee is already assigned to (V95 seed).

-- ── 1. Link penalization_policy_versions -> penalisation_policies ──────────────────────────
-- Existing versions' policy_id values are internal document ids with no matching
-- penalisation_policies row (the two concepts were unrelated before this migration) — reassign
-- them to the one seeded policy every employee is already assigned to, then enforce the FK.
UPDATE penalization_policy_versions
SET policy_id = (SELECT id FROM penalisation_policies ORDER BY created_at ASC LIMIT 1)
WHERE policy_id NOT IN (SELECT id FROM penalisation_policies);

ALTER TABLE penalization_policy_versions
    ADD CONSTRAINT fk_penalization_policy_versions_policy
    FOREIGN KEY (policy_id) REFERENCES penalisation_policies(id);

-- ── 2. penalisation_policies: audit + status, so a policy is a real manageable record ──────
ALTER TABLE penalisation_policies
    ADD COLUMN IF NOT EXISTS created_by UUID,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE'));

-- ── 3. Basic Information (Section 7-9): deduction method, leave priority, buffer, notice period ──
ALTER TABLE penalization_policy_versions
    ADD COLUMN IF NOT EXISTS deduction_method VARCHAR(20) NOT NULL DEFAULT 'LOSS_OF_PAY'
        CHECK (deduction_method IN ('LOSS_OF_PAY', 'PAID_LEAVE')),
    -- Comma-separated LeaveType.code values in priority order, e.g. 'SICK,CASUAL,PAID' — only
    -- meaningful when deduction_method = 'PAID_LEAVE'.
    ADD COLUMN IF NOT EXISTS leave_priority_order VARCHAR(500),
    ADD COLUMN IF NOT EXISTS buffer_period_days INTEGER,
    ADD COLUMN IF NOT EXISTS notice_period_forces_lop_enabled BOOLEAN NOT NULL DEFAULT false;

-- ── 4. Widen exempt-period cycle options to include WEEK alongside MONTH ────────────────────
ALTER TABLE penalization_policy_versions DROP CONSTRAINT IF EXISTS penalization_policy_versions_la_exempt_period_check;
ALTER TABLE penalization_policy_versions ADD CONSTRAINT penalization_policy_versions_la_exempt_period_check
    CHECK (la_exempt_period IN ('WEEK', 'MONTH'));

ALTER TABLE penalization_policy_versions DROP CONSTRAINT IF EXISTS penalization_policy_versions_ml_exempt_period_check;
ALTER TABLE penalization_policy_versions ADD CONSTRAINT penalization_policy_versions_ml_exempt_period_check
    CHECK (ml_exempt_period IN ('WEEK', 'MONTH'));

-- ── 5. Notice period (Section 9) — did not exist anywhere in the schema ─────────────────────
-- "Under notice" for a given attendance date = notice_period_start_date <= date <= last_working_day
-- (both set). A last_working_day with no start date is treated as "under notice from today
-- onward" by the engine, for records created before a start date was known.
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS notice_period_start_date DATE,
    ADD COLUMN IF NOT EXISTS last_working_day DATE;

-- ── 6. Deduction outcome on attendance_penalties (Section 7, 39, 48) ────────────────────────
ALTER TABLE attendance_penalties
    ADD COLUMN IF NOT EXISTS deduction_method VARCHAR(20),
    ADD COLUMN IF NOT EXISTS leave_deduction_days NUMERIC(4,2),
    ADD COLUMN IF NOT EXISTS lop_days NUMERIC(4,2),
    -- JSON snapshot of {leaveTypeCode: daysDeducted} for audit/report traceability (Section 39).
    ADD COLUMN IF NOT EXISTS leave_breakdown TEXT;
