-- NForce OneHR — Flyway Migration V107
-- Penalization Policy Phase 2: Late Arrival Total-Hours basis + combined-rule behavior +
-- caused-by-missing-log suppression, and No Attendance's adjoining-holiday/adjoining-week-off
-- sandwich rules (explicitly called out as NOT implemented in ConfiguredAttendancePolicyEngine's
-- Phase 1 javadoc — this migration is the schema half of closing that gap).
-- All additive/nullable-or-defaulted — no existing version becomes invalid.

-- ── Late Arrival: Total Hours basis (Section 25, 29, 31-33) ─────────────────────────────────
ALTER TABLE penalization_policy_versions DROP CONSTRAINT IF EXISTS penalization_policy_versions_la_basis_check;
ALTER TABLE penalization_policy_versions ADD CONSTRAINT penalization_policy_versions_la_basis_check
    CHECK (la_basis IN ('NUMBER_OF_INCIDENTS', 'TOTAL_HOURS'));

ALTER TABLE penalization_policy_versions
    -- "Allowed X hours/[cycle]" — only meaningful when la_basis = TOTAL_HOURS.
    ADD COLUMN IF NOT EXISTS la_allowed_hours NUMERIC(5,2),
    -- When both incident-count and total-hours thresholds are exceeded the same evaluation.
    ADD COLUMN IF NOT EXISTS la_combined_rule_behavior VARCHAR(20) NOT NULL DEFAULT 'TOTAL_HOURS_ONLY'
        CHECK (la_combined_rule_behavior IN ('TOTAL_HOURS_ONLY', 'BOTH')),
    -- "Penalise any late arrival caused by missing logs" — false preserves today's behavior
    -- exactly (no special-casing) for every already-saved version.
    ADD COLUMN IF NOT EXISTS la_penalise_when_caused_by_missing_log_enabled BOOLEAN NOT NULL DEFAULT false;

-- "Total Late Hours in Shift | Leave Deduction" tiered rule table — same shape/contract as
-- penalization_policy_work_hours_tiers (variable-length, editable/addable rows).
CREATE TABLE IF NOT EXISTS penalization_policy_late_hours_tiers (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_version_id     UUID          NOT NULL REFERENCES penalization_policy_versions(id),
    threshold_hours       NUMERIC(5,2)  NOT NULL,
    deduction_days        NUMERIC(4,2)  NOT NULL,
    sort_order            INTEGER       NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_penalization_policy_late_hours_tiers_version
    ON penalization_policy_late_hours_tiers (policy_version_id);

-- ── No Attendance: adjoining-holiday / adjoining-week-off sandwich rules (Section 12-13) ────
ALTER TABLE penalization_policy_versions
    ADD COLUMN IF NOT EXISTS na_adjoining_holiday_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS na_adjoining_holiday_condition VARCHAR(20)
        CHECK (na_adjoining_holiday_condition IN ('SANDWICHED', 'BEFORE', 'AFTER', 'ANY')),
    ADD COLUMN IF NOT EXISTS na_adjoining_holiday_calendar_day_threshold INTEGER,
    ADD COLUMN IF NOT EXISTS na_adjoining_holiday_ignore_half_day_leave BOOLEAN NOT NULL DEFAULT true,

    ADD COLUMN IF NOT EXISTS na_adjoining_weekoff_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS na_adjoining_weekoff_condition VARCHAR(20)
        CHECK (na_adjoining_weekoff_condition IN ('SANDWICHED', 'BEFORE', 'AFTER', 'ANY')),
    ADD COLUMN IF NOT EXISTS na_adjoining_weekoff_calendar_day_threshold INTEGER,
    ADD COLUMN IF NOT EXISTS na_adjoining_weekoff_ignore_half_day_leave BOOLEAN NOT NULL DEFAULT true;
