-- Roles & Permissions enhancement, Phase 1 — close an authorization gap on Projects.
--
-- PUT /api/projects/{id} (edit any project: owner, status, dates) and the /api/projects/clients
-- endpoints had no permission check at all, so any authenticated user could call them. Editing a
-- project now needs its own permission, projects:EDIT:ALL, seeded to exactly the roles that could edit
-- through the UI before: HR_ADMIN and SUPER_ADMIN. MANAGER never had a project edit screen (Project
-- Allocations is gated by project_allocations:CREATE:ALL) and gets nothing.
--
-- Additive and idempotent. This only RESTRICTS access: nobody is granted anything they could not
-- already do through the unprotected endpoint.
-- Rollback: DELETE FROM role_permissions rp USING permissions p, modules m
--           WHERE rp.permission_id = p.id AND p.module_id = m.id AND m.code = 'projects' AND p.action = 'EDIT';
--           DELETE FROM permissions p USING modules m
--           WHERE p.module_id = m.id AND m.code = 'projects' AND p.action = 'EDIT';
INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('projects', 'EDIT')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code
ON CONFLICT (module_id, action) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('HR_ADMIN',    'projects', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'projects', 'EDIT', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;
