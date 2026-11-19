-- `payslips.released` has always existed but nothing ever set it to true, so employee-facing
-- visibility (My Finance) was gated purely on payroll_run.status instead. Wiring it up as the real
-- "published to employee portal" gate (PayrollRunService#distributePayslips) would otherwise yank
-- away payslips employees can already see today — backfill true for everything already
-- Approved/Disbursed so this change is invisible to anyone who's already been given access.
-- Anything generated from here on starts unreleased until HR explicitly distributes it.

UPDATE payslips p
SET released = true
FROM payroll_runs r
WHERE p.payroll_run_id = r.id
  AND r.status IN ('APPROVED', 'DISBURSED');
