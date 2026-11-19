-- NForce OneHR — Flyway Migration V275
-- Payroll Epic (ONEHR-579): restrict payroll access to Super Admin only by default.
--
-- V248.1 seeded payroll:VIEW:ALL and payroll:CONFIGURE:ALL to both HR_ADMIN and SUPER_ADMIN.
-- Per explicit product decision, payroll is now Super-Admin-only out of the box: HR_ADMIN (and
-- every other role) must be granted payroll access explicitly through Roles & Permissions, the
-- same mechanism any custom role would use. SUPER_ADMIN is untouched here — V233's trigger
-- guarantees it holds every permission regardless, so there is nothing to re-grant for it.
--
-- Safe to run more than once: the DELETE is a no-op once the rows are gone.
DELETE FROM role_permissions rp
USING roles r, permissions p, modules m
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND p.module_id = m.id
  AND r.code = 'HR_ADMIN'
  AND m.code = 'payroll';
