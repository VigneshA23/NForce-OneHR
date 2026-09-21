-- Snapshots the Workflow Studio routing decision on the claim AT SUBMISSION TIME (see
-- ApprovalRuleEvaluationService/ExpenseService#submit), never re-derived later — so an in-flight
-- claim's approval requirements can never unexpectedly change just because an admin edited,
-- activated or deactivated a rule after this claim was already submitted (managerApprove reads
-- this stored flag, not the live rule).
--
-- Backfill: every existing claim was submitted back when the second/HR stage was unconditionally
-- required for everyone (the only behavior that has ever existed until this migration), so
-- defaulting every pre-existing row to true preserves their real approval requirements exactly —
-- not a guess, a fact about this column's absence until now.
ALTER TABLE expense_claims
    ADD COLUMN requires_second_approval BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN evaluated_rule_id UUID;
