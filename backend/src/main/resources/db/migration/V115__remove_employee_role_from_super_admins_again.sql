-- NForce OneHR — Flyway Migration V115
-- Data fix: remove the base EMPLOYEE role from every Super Admin account, again.
--
-- V111 removed it (Super Admin's self-service Attendance dashboard was unintended).
-- V112 restored it, reconsidering Super Admin as "staff too."
-- That reconsideration has itself been reversed — Super Admin is confirmed to be a
-- system/oversight role only, with no personal attendance record of its own. This
-- removes the EMPLOYEE role from any Super Admin account that has it, same as V111.

DELETE FROM user_roles
WHERE role_id = (SELECT id FROM roles WHERE code = 'EMPLOYEE')
  AND user_id IN (
      SELECT user_id FROM user_roles
      WHERE role_id = (SELECT id FROM roles WHERE code = 'SUPER_ADMIN')
  );
