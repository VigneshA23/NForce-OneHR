-- US-C02 (Epic ONEHR-579) follow-up: employee_compensation didn't record which PayGroup a given
-- CTC/structure assignment belongs to. Needed now that the Payroll Run console lets HR assign
-- compensation directly (there's still no separate employee-to-pay-group assignment mechanism —
-- see PayrollRunService#generateDraft's own note on that gap — so this is the first real place
-- that relationship gets recorded).
--
-- IF NOT EXISTS: a migration adding this exact column was run directly against the shared dev
-- database at some point (flyway_schema_history records version 267, "add pay group to employee
-- compensation", as successfully applied) but its file was never committed to any branch -
-- confirmed via `git log --all` finding nothing. This migration is deliberately idempotent so it
-- applies cleanly to that already-mutated dev database (a no-op there) and correctly to any fresh
-- environment that was never touched by the missing V267.
ALTER TABLE employee_compensation ADD COLUMN IF NOT EXISTS pay_group_id uuid REFERENCES pay_groups(id);
ALTER TABLE employee_compensation ALTER COLUMN pay_group_id SET NOT NULL;
