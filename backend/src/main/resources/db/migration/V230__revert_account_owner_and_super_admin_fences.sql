-- NForce OneHR — Flyway Migration V230
-- Reverts V228 (account_owner_and_super_admin_fences) and V229 (payroll_admin_role) in full —
-- the Account Owner / Super Admin fencing user story was reverted from the codebase, so this
-- undoes its effect on the already-migrated shared dev database too (removing the migration
-- files alone does not roll back schema/data that already applied).

-- V229: PAYROLL_ADMIN role
DELETE FROM user_roles
WHERE role_id IN (SELECT id FROM roles WHERE code = 'PAYROLL_ADMIN');

DELETE FROM role_permissions
WHERE role_id IN (SELECT id FROM roles WHERE code = 'PAYROLL_ADMIN');

DELETE FROM roles WHERE code = 'PAYROLL_ADMIN';

-- V228: fences table, Account Owner flag, payroll stub module, HR_ADMIN user_management:VIEW grant
DROP TABLE IF EXISTS super_admin_module_fences;

DELETE FROM role_permissions
WHERE permission_id IN (
    SELECT p.id FROM permissions p JOIN modules m ON m.id = p.module_id WHERE m.code = 'payroll'
);

DELETE FROM role_permissions
WHERE role_id IN (SELECT id FROM roles WHERE code = 'HR_ADMIN')
  AND permission_id IN (
      SELECT p.id FROM permissions p JOIN modules m ON m.id = p.module_id
      WHERE m.code = 'user_management' AND p.action = 'VIEW'
  );

DELETE FROM permissions
WHERE module_id IN (SELECT id FROM modules WHERE code = 'payroll');

DELETE FROM modules WHERE code = 'payroll';

ALTER TABLE users DROP COLUMN IF EXISTS is_account_owner;
