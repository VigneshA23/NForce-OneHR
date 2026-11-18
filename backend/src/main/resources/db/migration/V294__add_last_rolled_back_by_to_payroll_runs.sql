-- Rollback already records when (last_rolled_back_at) and why (last_rollback_reason), but not
-- who — with more than one Super Admin able to roll a run back, that's needed to show at the
-- bottom of the run detail page who did it.
ALTER TABLE payroll_runs ADD COLUMN last_rolled_back_by uuid;
