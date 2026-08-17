-- NForce OneHR — Flyway Migration V112
-- Data fix: re-grant the base EMPLOYEE role to Super Admin accounts.
--
-- V111 stripped EMPLOYEE from Super Admin accounts on the premise that Super Admin is an
-- oversight-only role. That premise has been reversed — Super Admin now gets the same
-- self-service Attendance Dashboard as every other role, via UserManagementService.rolesFor().
-- This migration grants EMPLOYEE to any Super Admin account that doesn't already hold it.

INSERT INTO user_roles (user_id, role_id)
SELECT ur.user_id, (SELECT id FROM roles WHERE code = 'EMPLOYEE')
FROM user_roles ur
WHERE ur.role_id = (SELECT id FROM roles WHERE code = 'SUPER_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur2
      WHERE ur2.user_id = ur.user_id
        AND ur2.role_id = (SELECT id FROM roles WHERE code = 'EMPLOYEE')
  );
