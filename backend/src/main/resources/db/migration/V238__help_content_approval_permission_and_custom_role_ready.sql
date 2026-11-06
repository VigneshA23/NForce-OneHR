-- NForce OneHR — Flyway Migration V238
-- Helpdesk / HR Help Content / Reports / Audit: role-code checks in the service layer replaced by
-- permission checks, so a custom role granted these permissions gets the same behavior as the
-- system roles that hold them. EXACT EQUIVALENCE for system roles — each check now uses a
-- permission whose seeded holder set is exactly the role set it used to name:
--   * HR_ADMIN + SUPER_ADMIN checks -> helpdesk:VIEW/EDIT:ALL, hr_help_content:VIEW/CREATE/EDIT/
--     DELETE:ALL, reports:VIEW/EXPORT:ALL, employees:VIEW:ALL (all seeded to exactly HR+SA in V225).
--   * SUPER_ADMIN-only checks -> hr_help_content:APPROVE:ALL (new, below) for Help Content's
--     fallback approval authority (decide/withdraw anyone's submission, see every pending attempt,
--     fallback approver when the author's chain has no active manager), and the existing
--     user_management:VIEW:ALL (SUPER_ADMIN-only) for seeing ACCESS_CONTROL audit actions.
-- Left role-based on purpose: notification recipient fan-out, help-content audience targeting
-- (RoleUtils.audienceBuckets — content targeting, not access), display labels.
--
-- Recovery: delete the hr_help_content APPROVE role_permissions + permissions rows and set
-- custom_role_ready back to FALSE for the four modules below. Check flyway_schema_history on the
-- shared DB before relying on V238 being free (see V224's header).

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('hr_help_content', 'APPROVE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code;

-- SUPER_ADMIN only — the role HelpContentService checked by code. (V233's trigger already granted
-- it on the insert above; listed for readability, hence ON CONFLICT.)
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('SUPER_ADMIN', 'hr_help_content', 'APPROVE', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE modules SET custom_role_ready = TRUE WHERE code IN ('helpdesk', 'hr_help_content', 'reports', 'audit');
