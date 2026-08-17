-- NForce OneHR — Flyway Migration V116
-- Data fix: re-grant the base EMPLOYEE role to Super Admin accounts, again.
--
-- V111 removed it. V112 restored it. V115 removed it again. This re-grants EMPLOYEE to
-- any Super Admin account that doesn't already have it, matching UserManagementService
-- .rolesFor() — Super Admin gets the same self-service Attendance dashboard as every
-- other role.

INSERT INTO user_roles (user_id, role_id)
SELECT ur.user_id, (SELECT id FROM roles WHERE code = 'EMPLOYEE')
FROM user_roles ur
WHERE ur.role_id = (SELECT id FROM roles WHERE code = 'SUPER_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur2
      WHERE ur2.user_id = ur.user_id
        AND ur2.role_id = (SELECT id FROM roles WHERE code = 'EMPLOYEE')
  );
