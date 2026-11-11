-- Roles & Permissions hardening, release R1 — "team responsibility" permission.
--
-- employees:VIEW:TEAM is the explicit organisational-responsibility marker for managing a team:
--   * GET /api/employees/my-reports (a manager's direct-report dashboard) now requires it, so a
--     reporting relationship alone no longer exposes a team's data;
--   * (release R2) it is what makes a user eligible to be selected as someone's reporting manager.
-- MANAGER keeps exactly today's behaviour by being seeded here. HR_ADMIN/SUPER_ADMIN already hold
-- employees:VIEW:ALL, which cascades to TEAM. A custom role (e.g. "Team Lead") may be granted it
-- from Roles & Permissions; a custom role holding only Roles & Permissions access does not qualify.
--
-- Additive and idempotent: the employees:VIEW permission already exists, only the grant is new.
-- Rollback: DELETE FROM role_permissions rp USING roles r, permissions p, modules m
--           WHERE rp.role_id = r.id AND rp.permission_id = p.id AND p.module_id = m.id
--             AND r.code = 'MANAGER' AND m.code = 'employees' AND p.action = 'VIEW' AND rp.scope = 'TEAM';
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('MANAGER', 'employees', 'VIEW', 'TEAM')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;
