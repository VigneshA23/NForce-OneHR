-- Phase 3: Effective vs Gross Hours, Daily/Weekly/Monthly frequency, exclude-hours-outside-shift,
-- and missing-logs-causes-shortage are all consumed as configuration on the existing
-- whs_deduction_basis/whs_deduction_period String columns (widened only at the DTO validation
-- layer — no column-type change needed) plus two new boolean flags below. Existing rows already
-- store 'EFFECTIVE_HOURS'/'DAY' (or NULL, treated as those defaults by the engine) and are
-- untouched by this migration, so every existing policy keeps behaving exactly as before.

ALTER TABLE penalization_policy_versions
    ADD COLUMN whs_exclude_hours_outside_shift_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE penalization_policy_versions
    ADD COLUMN whs_penalize_shortage_caused_by_missing_logs_enabled BOOLEAN NOT NULL DEFAULT FALSE;
