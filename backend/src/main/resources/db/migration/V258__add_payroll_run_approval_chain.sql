-- US-C09 (ONEHR-579) — wires payroll_runs into the same ApprovalRule chain EXPENSE already uses.
-- Status stays SUBMITTED across every intermediate approval stage (matching the already-settled
-- Decision #2 status vocabulary) — only pending_approval_stage advances, same shape as
-- expense_claims.pending_final_stage (V203).

ALTER TABLE payroll_runs
    ADD COLUMN approval_stages varchar(100),
    ADD COLUMN pending_approval_stage varchar(20),
    ADD COLUMN evaluated_rule_id uuid;
