-- Employee Weekly Off Policy Assignment history: makes WHICH Policy an employee is assigned to
-- effective-dated, the same model V172 (employee_shift_assignments) already applied to WHICH
-- Shift an employee is assigned to. See EmployeeWeeklyOffPolicyAssignment's own Javadoc for the
-- full design — summary: every date-aware consumer resolves "the latest assignment whose
-- effective_from <= date" (EmployeeWeeklyOffPolicyAssignmentResolver), so a future-dated
-- reassignment can never retroactively affect an already-recorded historical calculation for a
-- date before the new assignment takes effect.
--
-- employees.weekly_off_policy_id is UNCHANGED by this migration and remains in the schema — it
-- becomes a best-effort, non-authoritative display/roster/filter cache going forward (see
-- application-code changes alongside this migration); it is not read here for anything but the
-- one-time backfill below.
CREATE TABLE IF NOT EXISTS employee_weekly_off_policy_assignments (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Mirrors employee_shift_assignments' identical per-employee-assignment-history shape —
    -- employees(user_id), no cascade (employees are deactivated, never hard-deleted).
    employee_user_id       UUID         NOT NULL REFERENCES employees(user_id),
    weekly_off_policy_id   UUID         NOT NULL REFERENCES weekly_off_policies(id),
    effective_from         DATE         NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by             UUID,
    -- At most one assignment of a given employee may take effect on any single date — mirrors
    -- uq_employee_shift_assignments_employee_effective_from, and is what makes "replace the
    -- pending assignment" (EmployeeAssignmentService) a safe delete-then-insert.
    CONSTRAINT uq_employee_weekly_off_policy_assignments_employee_effective_from UNIQUE (employee_user_id, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_employee_weekly_off_policy_assignments_employee_effective_from
    ON employee_weekly_off_policy_assignments (employee_user_id, effective_from);

-- Read-only anomaly check, mirroring V172's identical gate: a real Attendance row dated before its
-- employee's own joining_date would mean the backfilled assignment below (effective from
-- joining_date) doesn't cover it — any historical weekly-off calculation for that date would then
-- correctly fall through to the existing Saturday/Sunday fallback rather than a silently wrong
-- policy. That's the correct, safe behavior, not a migration blocker — this only WARNS.
DO $$
DECLARE
    anomaly_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO anomaly_count
    FROM attendance_records a
    JOIN employees e ON e.user_id = a.employee_user_id
    WHERE a.work_date < e.joining_date;

    IF anomaly_count > 0 THEN
        RAISE NOTICE 'employee_weekly_off_policy_assignments backfill: % attendance_records row(s) have a work_date before their employee''s own joining_date — the backfilled assignment (effective from joining_date) will not cover them; any historical weekly-off calculation for those specific dates will fall through to the Saturday/Sunday default rather than guess. This is a pre-existing data anomaly, not something this migration can safely correct.', anomaly_count;
    END IF;
END $$;

-- Backfill: one initial assignment per existing employee that currently has a Weekly Off Policy
-- assigned at all, anchored to their own joining_date — the same domain-meaningful "employment
-- start" fact V172's own backfill uses, for the identical reason (no better field exists, and this
-- is NOT a claim that the policy "changed" at any point in the employee's history — it is a
-- resolution-infrastructure backfill, not a historical reconstruction: today's code already
-- unconditionally treats the CURRENT policy as applicable to every date including the past, so
-- this preserves that exact existing behavior for every date from joining_date onward rather than
-- inventing a different one). Employees with no policy assigned (weekly_off_policy_id IS NULL) are
-- deliberately skipped — D1 forbids inventing an assignment that was never made; they continue to
-- resolve via the existing Saturday/Sunday fallback, unchanged.
INSERT INTO employee_weekly_off_policy_assignments (employee_user_id, weekly_off_policy_id, effective_from, created_at)
SELECT user_id, weekly_off_policy_id, joining_date, created_at
FROM employees
WHERE weekly_off_policy_id IS NOT NULL;
