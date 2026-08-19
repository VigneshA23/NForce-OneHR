-- NForce OneHR — Flyway Migration V111
-- Data fix: revert the erroneous EMPLOYEE role grant to Super Admin accounts.
--
-- UserManagementService.rolesFor() briefly granted every Super Admin the base EMPLOYEE
-- role alongside their admin role, giving them a personal self-service Attendance
-- Dashboard (Check-in/Check-out, Web Check-in, Work From Home, Partial Day Request, own
-- attendance log/calendar) they should never have had — Super Admin is a system/oversight
-- role only, not staff with an attendance record of their own. That code has been reverted;
-- this migration removes the EMPLOYEE role from any Super Admin account that already picked
-- it up while the bug was live.

DELETE FROM user_roles
WHERE role_id = (SELECT id FROM roles WHERE code = 'EMPLOYEE')
  AND user_id IN (
      SELECT user_id FROM user_roles
      WHERE role_id = (SELECT id FROM roles WHERE code = 'SUPER_ADMIN')
  );
