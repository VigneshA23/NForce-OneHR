-- NForce OneHR — Flyway Migration V234
-- leave / regularization / exceptions / attendance: the last role-code checks in these modules'
-- services (LeaveService, RegularizationService, ExceptionService, AttendancePenaltyService,
-- AttendanceController) are now permission checks, so a custom role granted these permissions
-- behaves exactly like the system roles that hold them. Each check was mapped to a permission
-- whose seeded holders are EXACTLY the role set the code checked before:
--   leave           "HR_ADMIN or SUPER_ADMIN" override         -> leave:APPROVE:ALL          (V225: HR_ADMIN, SUPER_ADMIN)
--   regularization  "HR_ADMIN or SUPER_ADMIN" override         -> regularization:APPROVE:ALL (V225: HR_ADMIN, SUPER_ADMIN)
--                   history "HR/SA see anyone"                 -> regularization:VIEW:ALL    (V225: HR_ADMIN, SUPER_ADMIN)
--                   "is MANAGER" (assigned/direct-report path) -> regularization:APPROVE:TEAM without ALL (V225: MANAGER)
--   exceptions      "HR/SA org-wide, MANAGER team, else 403"   -> exceptions:VIEW:ALL / :TEAM
--   attendance      penalties list "HR/SA org-wide"            -> attendance:VIEW:ALL
--
-- NEW PERMISSIONS (the behaviors below had no existing permission with exactly their holders):
--   regularization:EXPORT     SUPER_ADMIN only. GET /attendance/regularization/all (org-wide full
--                             history with filters) was SUPER_ADMIN-only via a literal ROLE_SUPER_ADMIN
--                             check on top of regularization:VIEW:ALL, which HR_ADMIN also holds.
--   regularization:CONFIGURE  SUPER_ADMIN only. Exemption from the regularization submission policy
--                             (lookback window + monthly limit), previously actorHasRole("SUPER_ADMIN").
--   attendance:EDIT           MANAGER TEAM, HR_ADMIN ALL, SUPER_ADMIN ALL — exactly today's holders of
--                             attendance:VIEW. Security fix: POST /attendance/penalties/cancel (a write)
--                             was gated only by attendance:VIEW:TEAM; it now requires attendance:EDIT:TEAM,
--                             and its org-wide branch (was "HR_ADMIN or SUPER_ADMIN") is attendance:EDIT:ALL.
--                             Same holders as before, so nobody gains or loses access today — but a
--                             custom role can now be given read-only penalty access.
-- SUPER_ADMIN rows are listed for readability; V233's trigger already granted them on insert.
--
-- LEFT AS ROLE-CODE LOGIC (deliberately — business routing tied to the literal Super Admin role,
-- not an access grant): a SUPER_ADMIN's OWN leave request routes to their current manager (or
-- themselves), and the regularization "Assign To" picker leaves out users whose only qualifying role
-- is SUPER_ADMIN. Neither depends on what the ACTOR's custom role was granted.
--
-- Rollback: DELETE the role_permissions/permissions rows for regularization:EXPORT,
-- regularization:CONFIGURE and attendance:EDIT, and set custom_role_ready back to FALSE for the four
-- modules below. Check flyway_schema_history on the shared DB before relying on V234 being free.

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('regularization', 'EXPORT'), ('regularization', 'CONFIGURE'),
    ('attendance',     'EDIT')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('SUPER_ADMIN', 'regularization', 'EXPORT', 'ALL'),
    ('SUPER_ADMIN', 'regularization', 'CONFIGURE', 'ALL'),

    ('MANAGER',     'attendance', 'EDIT', 'TEAM'),
    ('HR_ADMIN',    'attendance', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'attendance', 'EDIT', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE modules SET custom_role_ready = TRUE
WHERE code IN ('leave', 'regularization', 'exceptions', 'attendance');
