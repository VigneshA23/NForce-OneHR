-- Roles & Permissions enhancement, Phase 3 — "Audit & Security" becomes its own permission.
--
-- /audit shows two different things: Audit History (a person's own audited activity, audit:VIEW:OWN)
-- and Audit & Security, which additionally includes the access-control events (user and role
-- administration). The second was decided by "holds user_management:VIEW:ALL" — an unrelated
-- permission that only happened to be Super-Admin-only. It now has a node of its own,
-- audit_security:VIEW:ALL.
--
-- No one's access changes: every role that holds user_management:VIEW at ALL scope today (SUPER_ADMIN
-- in the seed; a custom role would be copied too) receives audit_security:VIEW:ALL, which is exactly the
-- set that could see access-control events before. Additive and idempotent.
-- Rollback: DELETE FROM role_permissions rp USING permissions p, modules m
--           WHERE rp.permission_id = p.id AND p.module_id = m.id AND m.code = 'audit_security';
--           DELETE FROM permissions p USING modules m WHERE p.module_id = m.id AND m.code = 'audit_security';
--           DELETE FROM modules WHERE code = 'audit_security';
INSERT INTO modules (code, display_name, custom_role_ready) VALUES
    ('audit_security', 'Audit & Security', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('audit_security', 'VIEW')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code
ON CONFLICT (module_id, action) DO NOTHING;

-- SUPER_ADMIN also gets it from the V233 trigger; listed so the seed matrix reads straight off the SQL.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('SUPER_ADMIN', 'audit_security', 'VIEW', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Preserve access exactly: whoever could see access-control events through user_management:VIEW:ALL keeps seeing them.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT rp.role_id, np.id, 'ALL'
FROM role_permissions rp
JOIN permissions op ON op.id = rp.permission_id AND op.action = 'VIEW'
JOIN modules om ON om.id = op.module_id AND om.code = 'user_management'
JOIN modules nm ON nm.code = 'audit_security'
JOIN permissions np ON np.module_id = nm.id AND np.action = 'VIEW'
WHERE rp.scope = 'ALL'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Hierarchy + scope range + description (same tuple conventions as V278; see permission-catalogue.yml).
UPDATE modules m SET parent_id = p.id, node_type = v.node_type, sort_order = v.sort_order, sensitivity = v.sensitivity
FROM (VALUES
    ('audit_security', 'mod.reports_audit', 'SUBMODULE', 30, 'HIGH')
) AS v(code, parent_code, node_type, sort_order, sensitivity)
LEFT JOIN modules p ON p.code = NULLIF(v.parent_code, '')
WHERE m.code = v.code;

UPDATE modules SET description = 'Includes access-control events (user and role administration) in the signed-in user''s audit view.'
WHERE code = 'audit_security';

UPDATE permissions p SET min_scope = v.min_scope, max_scope = v.max_scope
FROM (VALUES
    ('audit_security', 'VIEW', 'ALL', 'ALL')
) AS v(module_code, action, min_scope, max_scope)
JOIN modules mo ON mo.code = v.module_code
WHERE p.module_id = mo.id AND p.action = v.action;
