-- Roles & Permissions hardening, release R4 — FAQs & Guides approval becomes permission-driven.
--
-- Until now a piece of Help Content was decided by "its resolved approver" (the author's reporting
-- manager, or the APPROVE:ALL fallback) with no permission involved, and the Approval Center showed
-- the queue to anyone holding the MANAGER / HR_ADMIN / SUPER_ADMIN role code. A reporting line alone
-- must not authorize a decision, so deciding now needs hr_help_content:APPROVE:TEAM (cascaded: the
-- V238 APPROVE:ALL, Super Admin by default, also satisfies it) AND being the resolved approver.
--
-- The permission itself already exists (V238); this only seeds the TEAM grant to the roles that could
-- act before, so no existing approver loses access. A custom role may be granted it from Roles &
-- Permissions. Additive and idempotent.
-- Rollback: DELETE FROM role_permissions rp USING roles r, permissions p, modules m
--           WHERE rp.role_id = r.id AND rp.permission_id = p.id AND p.module_id = m.id
--             AND m.code = 'hr_help_content' AND p.action = 'APPROVE' AND rp.scope = 'TEAM'
--             AND r.code IN ('MANAGER', 'HR_ADMIN');
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('MANAGER',  'hr_help_content', 'APPROVE', 'TEAM'),
    ('HR_ADMIN', 'hr_help_content', 'APPROVE', 'TEAM')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;
