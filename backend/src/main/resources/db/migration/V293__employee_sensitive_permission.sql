-- Sensitive data protection, Phase 1 — sensitive employee fields get their own permission.
--
-- Bank details, government IDs, passport, DOB, personal contact and address were visible to anyone
-- holding employees:VIEW:ALL (the whole employee master). They now sit behind employee_sensitive:
--   VIEW     see them unmasked on another employee's profile (every such view is audit-logged)
--   EDIT     enter them for someone else (e.g. on the create-user wizard)
--   APPROVE  approve an employee's own change to bank / government-ID fields (Phase 2)
--
-- No one's access changes: HR_ADMIN and SUPER_ADMIN get all three (they could see and enter these
-- fields before), and every role holding employees:VIEW:ALL today (a custom role included) is
-- copied to employee_sensitive:VIEW:ALL, which is exactly the set that saw these fields before.
-- Additive and idempotent.
-- Rollback: DELETE FROM role_permissions rp USING permissions p, modules m
--           WHERE rp.permission_id = p.id AND p.module_id = m.id AND m.code = 'employee_sensitive';
--           DELETE FROM permissions p USING modules m WHERE p.module_id = m.id AND m.code = 'employee_sensitive';
--           DELETE FROM modules WHERE code = 'employee_sensitive';
INSERT INTO modules (code, display_name, custom_role_ready) VALUES
    ('employee_sensitive', 'Sensitive Employee Data', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('employee_sensitive', 'VIEW'),
    ('employee_sensitive', 'EDIT'),
    ('employee_sensitive', 'APPROVE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code
ON CONFLICT (module_id, action) DO NOTHING;

-- SUPER_ADMIN also gets these from the V233 trigger; listed so the seed matrix reads straight off the SQL.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('HR_ADMIN', 'employee_sensitive', 'VIEW', 'ALL'),
    ('HR_ADMIN', 'employee_sensitive', 'EDIT', 'ALL'),
    ('HR_ADMIN', 'employee_sensitive', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'employee_sensitive', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'employee_sensitive', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'employee_sensitive', 'APPROVE', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Preserve access exactly: whoever saw the sensitive fields through employees:VIEW:ALL keeps seeing them.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT rp.role_id, np.id, 'ALL'
FROM role_permissions rp
JOIN permissions op ON op.id = rp.permission_id AND op.action = 'VIEW'
JOIN modules om ON om.id = op.module_id AND om.code = 'employees'
JOIN modules nm ON nm.code = 'employee_sensitive'
JOIN permissions np ON np.module_id = nm.id AND np.action = 'VIEW'
WHERE rp.scope = 'ALL'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Hierarchy + scope range + description (same tuple conventions as V278; see permission-catalogue.yml).
UPDATE modules m SET parent_id = p.id, node_type = v.node_type, sort_order = v.sort_order, sensitivity = v.sensitivity
FROM (VALUES
    ('employee_sensitive', 'mod.people', 'SUBMODULE', 40, 'HIGH')
) AS v(code, parent_code, node_type, sort_order, sensitivity)
LEFT JOIN modules p ON p.code = NULLIF(v.parent_code, '')
WHERE m.code = v.code;

UPDATE modules SET description = 'Bank details, government IDs, passport, date of birth, personal contact and addresses. Without View these are masked everywhere; every unmasked view is audit-logged.'
WHERE code = 'employee_sensitive';

UPDATE permissions p SET min_scope = v.min_scope, max_scope = v.max_scope
FROM (VALUES
    ('employee_sensitive', 'VIEW', 'ALL', 'ALL'),
    ('employee_sensitive', 'EDIT', 'ALL', 'ALL'),
    ('employee_sensitive', 'APPROVE', 'ALL', 'ALL')
) AS v(module_code, action, min_scope, max_scope)
JOIN modules mo ON mo.code = v.module_code
WHERE p.module_id = mo.id AND p.action = v.action;
