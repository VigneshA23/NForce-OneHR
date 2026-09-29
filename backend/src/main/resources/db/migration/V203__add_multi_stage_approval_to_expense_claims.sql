-- Workflow Studio multi-layer expense approval (Manager -> HR Admin -> Super Admin): a claim
-- previously only knew WHETHER a second stage was required (requires_second_approval), so a rule
-- naming both HR Admin and Super Admin cleared the claim for payroll after HR alone and it never
-- reached Super Admin. approval_stages snapshots the rule's full ordered stage list at submission;
-- pending_final_stage is the role whose approval a MANAGER_APPROVED claim is waiting on next.
-- Both nullable: NULL on pre-existing claims means the original single final stage that either
-- HR Admin or Super Admin may clear (see ExpenseService#canActAtFinalStage).
-- IF NOT EXISTS: shared dev database multiple branches migrate against directly (see the flyway
-- config comment in application.yml).
ALTER TABLE expense_claims ADD COLUMN IF NOT EXISTS approval_stages VARCHAR(100);
ALTER TABLE expense_claims ADD COLUMN IF NOT EXISTS pending_final_stage VARCHAR(30);
