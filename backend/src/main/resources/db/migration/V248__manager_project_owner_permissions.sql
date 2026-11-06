-- US-B03 (project owner) on top of V241's permission gates: a Manager may list and allocate against
-- the projects they own. TEAM scope, so the gates are projects:VIEW:TEAM / project_allocations:CREATE:TEAM;
-- HR_ADMIN/SUPER_ADMIN's ALL grants already cascade to TEAM. Ownership itself is enforced in
-- ProjectService#list and ProjectAllocationService#requireCanAllocate, not by this scope.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('MANAGER', 'projects', 'VIEW', 'TEAM'),
    ('MANAGER', 'project_allocations', 'CREATE', 'TEAM')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;
