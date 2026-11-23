-- Finance becomes its own permission module (was riding on payroll:CONFIGURE:ALL under Payroll).
-- finance:CONFIGURE:ALL gates the Employee Finances console (/finances, /api/admin/finances/**).
-- Access is preserved: every role that holds payroll:CONFIGURE:ALL today gets it. Additive, idempotent.
INSERT INTO modules (code, display_name, custom_role_ready) VALUES
    ('mod.finance', 'Finance', FALSE),
    ('finance', 'Employee Finances', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('finance', 'CONFIGURE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code
ON CONFLICT (module_id, action) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT rp.role_id, np.id, 'ALL'
FROM role_permissions rp
JOIN permissions op ON op.id = rp.permission_id AND op.action = 'CONFIGURE'
JOIN modules om ON om.id = op.module_id AND om.code = 'payroll'
JOIN modules nm ON nm.code = 'finance'
JOIN permissions np ON np.module_id = nm.id AND np.action = 'CONFIGURE'
WHERE rp.scope = 'ALL'
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE modules m SET parent_id = p.id, node_type = v.node_type, sort_order = v.sort_order, sensitivity = v.sensitivity
FROM (VALUES
    ('mod.finance', '', 'MODULE', 105, 'NORMAL'),
    ('finance', 'mod.finance', 'SUBMODULE', 10, 'HIGH')
) AS v(code, parent_code, node_type, sort_order, sensitivity)
LEFT JOIN modules p ON p.code = NULLIF(v.parent_code, '')
WHERE m.code = v.code;

UPDATE modules SET description = 'Finance and tax administration.' WHERE code = 'mod.finance';
UPDATE modules SET description = 'Every employee''s finances - salary, payslips, declarations, proofs, previous income, Form 130/124, tax windows, partners, slabs and the finance audit log.'
WHERE code = 'finance';

UPDATE permissions p SET min_scope = v.min_scope, max_scope = v.max_scope
FROM (VALUES
    ('finance', 'CONFIGURE', 'ALL', 'ALL')
) AS v(module_code, action, min_scope, max_scope)
JOIN modules mo ON mo.code = v.module_code
WHERE p.module_id = mo.id AND p.action = v.action;
