-- Phase 4: Shift.workingDays ("Applicable Days") is removed. WeeklyOffPolicy — via the
-- effective-dated EmployeeWeeklyOffPolicyAssignment/WeeklyOffPolicyVersion model (V233/V234) — is
-- now the sole source of truth for an employee's working/weekly-off days, including the one
-- remaining consumer this column had (LeaveService#requireOnlyWorkingDays, migrated to resolve via
-- WorkingDayService's shared weekly-off batch resolver instead). No other production code, query,
-- or migration references shifts.working_days as of this migration — safe to drop. Forward-only;
-- no down-migration, consistent with every other migration in this project.
ALTER TABLE shifts DROP COLUMN IF EXISTS working_days;
