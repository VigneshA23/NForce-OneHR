-- An HR Admin/Super Admin approving at the Manager stage (an intentional override — see
-- ExpenseService#requireCurrentManagerOf) was recorded identically to the actual reporting
-- manager approving: same status, same generic "approved by your manager" notification, no way
-- to tell from the claim's own history that it was an override. manager_approved_by_role
-- snapshots which role actually decided the Manager stage (MANAGER / HR_ADMIN / SUPER_ADMIN) at
-- the moment of that decision, so the override is visible later even if the employee's reporting
-- manager changes afterward. Nullable: null on claims that predate this column, and while a claim
-- hasn't reached the Manager stage yet.
ALTER TABLE expense_claims ADD COLUMN IF NOT EXISTS manager_approved_by_role VARCHAR(30);
