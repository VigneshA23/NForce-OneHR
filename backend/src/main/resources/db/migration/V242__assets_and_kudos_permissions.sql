-- NForce OneHR — Flyway Migration V242
-- Assets + Kudos permission modules — replaces AssetService's / KudosService's hardcoded role-code
-- checks with permission checks. EXACT equivalence with today's code, per decision:
--
--   AssetService (no @PreAuthorize on AssetController; every gate lived in the service):
--   * requireAdmin (HR_ADMIN/SUPER_ADMIN) on listAllAssets / countAssets / hrTileSummary /
--     availableByCategory                         -> assets:VIEW:ALL     HR_ADMIN, SUPER_ADMIN
--   * requireAdmin on createAsset                 -> assets:CREATE:ALL   HR_ADMIN, SUPER_ADMIN
--   * requireAdmin on assign / reassign / markReturned / retire / fulfillRequest
--                                                 -> assets:EDIT:ALL     HR_ADMIN, SUPER_ADMIN
--   * assertCanDecideRequest (approve/reject): HR_ADMIN/SUPER_ADMIN any request
--                                                 -> assets:APPROVE:ALL  HR_ADMIN, SUPER_ADMIN
--     ... MANAGER role + requester is the actor's current direct report
--                                                 -> assets:APPROVE:TEAM MANAGER (+ the direct-report check, kept in code)
--   * listPendingForApprover's org-wide branch (PENDING + APPROVED, all requesters) was
--     isAdminRole                                 -> assets:APPROVE:ALL  (same holders)
--   KudosService:
--   * ORG_WIDE_KUDOS_ROLES (appreciate anyone org-wide)
--                                                 -> kudos:CREATE:ALL    HR_ADMIN, SUPER_ADMIN
--
-- Deliberately NOT gated / NOT seeded (no role check exists today — adding one would be a
-- regression, see V225's header): my assignments / acknowledge / submit / withdraw / my requests,
-- the manager team views (teamAssignments, teamRequests, managerTileSummary — scoped purely by the
-- caller's own direct reports for any user), listPendingForApprover's direct-reports branch, and
-- relationship-scoped kudos (own manager, peer, own direct report) — all stay open to everyone.
--
-- Both modules are fully migrated (enforcement is purely hasAuthority), so custom_role_ready = TRUE.
-- SUPER_ADMIN also gets these via V233's trigger; listed explicitly for readability.

INSERT INTO modules (code, display_name, custom_role_ready) VALUES
    ('assets', 'Assets',       TRUE),
    ('kudos',  'Appreciation', TRUE);

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('assets', 'VIEW'), ('assets', 'CREATE'), ('assets', 'EDIT'), ('assets', 'APPROVE'),
    ('kudos',  'CREATE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    -- assets — inventory admin + request decisions org-wide: HR/SA only (was requireAdmin/isAdminRole)
    ('HR_ADMIN',    'assets', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'assets', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'assets', 'EDIT', 'ALL'),
    ('HR_ADMIN',    'assets', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'assets', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'assets', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'assets', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'assets', 'APPROVE', 'ALL'),
    -- assets — decide a direct report's request (was: MANAGER role + direct-report check)
    ('MANAGER',     'assets', 'APPROVE', 'TEAM'),

    -- kudos — appreciate anyone org-wide (was ORG_WIDE_KUDOS_ROLES)
    ('HR_ADMIN',    'kudos', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'kudos', 'CREATE', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;
