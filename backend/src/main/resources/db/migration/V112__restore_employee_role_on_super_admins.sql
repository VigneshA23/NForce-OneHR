-- NForce OneHR — Flyway Migration V112
-- Data fix: restore the base EMPLOYEE role on every Super Admin account.
--
-- V111 removed it, treating the Super Admin self-service Attendance dashboard
-- (Check-in/Check-out, Web Check-in, Work From Home, Partial Day Request, own attendance
-- log/calendar) as an accidental grant to be undone. That direction has been reconsidered —
-- Super Admin is staff too and gets the same self-service attendance access as every other
-- role (see UserManagementService.rolesFor). This re-grants EMPLOYEE to any Super Admin
-- account that doesn't already have it.

INSERT INTO user_roles (user_id, role_id)
SELECT ur.user_id, (SELECT id FROM roles WHERE code = 'EMPLOYEE')
FROM user_roles ur
WHERE ur.role_id = (SELECT id FROM roles WHERE code = 'SUPER_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM user_roles ur2
      WHERE ur2.user_id = ur.user_id
        AND ur2.role_id = (SELECT id FROM roles WHERE code = 'EMPLOYEE')
  );
