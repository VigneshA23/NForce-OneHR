-- NForce OneHR — Flyway Migration V236
-- Expenses + Workflow Studio: role-code checks -> permission checks, and custom roles as approvers.
--
-- EQUIVALENCE (every system role keeps exactly today's behavior):
--   ExpenseService's old FINAL_APPROVER_ROLES = {HR_ADMIN, SUPER_ADMIN} checks now use the V225
--   expenses grants whose holder set is exactly that pair (MANAGER's TEAM grants never satisfy an
--   ALL check):
--     category create/update                          -> expenses:CONFIGURE:ALL
--     manager-stage override, receipt override,
--     HR_ADMIN / legacy final stage, mark paid        -> expenses:APPROVE:ALL
--     cleared-for-payroll list, HR tile               -> expenses:VIEW:ALL
--   The old "SUPER_ADMIN may act at the Super Admin stage / override any stage" check had no
--   SUPER_ADMIN-only permission to map to, so one is added below: expenses_override:APPROVE,
--   seeded to SUPER_ADMIN only (the same single holder as before).
--
-- CUSTOM ROLES AS APPROVERS (additive): a Workflow Studio stage may now also name an ACTIVE custom
-- role by its code; that role's holders act at that stage (plus the override holders). Existing
-- stage values (MANAGER / HR_ADMIN / SUPER_ADMIN) keep their meaning — no row is rewritten. The
-- stage CSV columns are widened so a few custom role codes (up to 30 chars each) fit.
--
-- Recovery: delete the role_permissions/permissions/modules rows for 'expenses_override', set
-- custom_role_ready back to FALSE for 'expenses', and (only if no stored value exceeds 100 chars)
-- narrow approval_stages back to VARCHAR(100).

INSERT INTO modules (code, display_name) VALUES
    ('expenses_override', 'Expenses: Super Admin Stage / Override Approval');

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('expenses_override', 'APPROVE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code;

-- V233's trigger has already granted this to SUPER_ADMIN; listed for readability.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('SUPER_ADMIN', 'expenses_override', 'APPROVE', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- approval_rules was already flipped by V232 (its gates were pure hasAuthority); kept in the list
-- because its stage validation now also understands custom roles.
UPDATE modules SET custom_role_ready = TRUE
WHERE code IN ('expenses', 'expenses_override', 'approval_rules');

ALTER TABLE approval_rules ALTER COLUMN approval_stages TYPE VARCHAR(500);
ALTER TABLE expense_claims ALTER COLUMN approval_stages TYPE VARCHAR(500);
