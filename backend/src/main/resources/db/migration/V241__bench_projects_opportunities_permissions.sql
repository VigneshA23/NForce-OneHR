-- NForce OneHR — Flyway Migration V241
-- Permissions for the Bench Management / Internal Opportunities / Projects & Allocations /
-- Skill Ratings / Compensation Bands features (V211-V223), which were built before the permission
-- engine and still gated by hasAnyRole(...) + role-code checks. Purely additive; reproduces
-- today's access EXACTLY for EMPLOYEE/MANAGER/HR_ADMIN/SUPER_ADMIN (every row below is a role the
-- code allowed before), and lets custom roles be granted these (custom_role_ready = TRUE).
--
-- MAPPING (old gate -> new permission):
--   bench (BenchStatusController)
--     GET  /api/bench-status/team                     MANAGER,HR,SA -> bench:VIEW:TEAM
--          (direct reports only for everyone, incl. HR/SA — unchanged)
--     GET  /api/bench-status, /dashboard, /cost,
--          /{id}/allocations, /aging-config            HR,SA         -> bench:VIEW:ALL
--     POST /api/bench-status                          HR,SA         -> bench:CREATE:ALL
--     PATCH /api/bench-status/{id}/end                HR,SA         -> bench:EDIT:ALL
--     PUT  /api/bench-status/aging-config             HR,SA         -> bench:CONFIGURE:ALL
--   compensation (CompensationBandController, class-level HR,SA)
--     GET  bands, GET assignments                                   -> compensation:VIEW:ALL
--     POST band                                                     -> compensation:CREATE:ALL
--     PUT  assignments                                              -> compensation:EDIT:ALL
--   opportunities (OpportunityController + OpportunityService)
--     POST /api/opportunities          MANAGER,HR,SA -> opportunities:CREATE:ALL
--     GET  /api/opportunities          ungated; "see every opportunity" branch (MANAGER,HR,SA)
--                                      -> opportunities:VIEW:ALL (others: OPEN ones while benched)
--     GET  /{id}/interests             MANAGER,HR,SA -> opportunities:VIEW:ALL gate; then the
--                                      poster, or anyone holding opportunities:APPROVE:ALL (HR,SA)
--     POST /{id}/close (assign)        HR,SA         -> opportunities:APPROVE:ALL
--     POST /{id}/interest              ungated self-service (benched employees) — unchanged
--     MANAGER gets ALL (not TEAM): opportunities are org-wide, not scoped to a manager's reports.
--   projects (ProjectController, class-level HR,SA)
--     GET -> projects:VIEW:ALL, POST -> projects:CREATE:ALL
--   project_allocations (ProjectAllocationController + ProjectAllocationService)
--     POST /api/allocations, /bulk     HR,SA         -> project_allocations:CREATE:ALL
--     GET  /api/allocations/capacity   MANAGER,HR,SA -> project_allocations:VIEW:TEAM gate;
--                                      org-wide (no direct-report ceiling) only with VIEW:ALL (HR,SA)
--   skill_ratings (SkillController + SkillService — US-A07 manager endorsement)
--     GET  /api/skill-ratings/team     MANAGER,HR,SA -> skill_ratings:VIEW:TEAM (direct reports only)
--     PUT  /{employeeId}/{skillId}/manager MANAGER,HR,SA -> skill_ratings:EDIT:TEAM gate; rating
--                                      a non-direct-report only with skill_ratings:EDIT:ALL (HR,SA)
--   NOT a new module — reuses existing permissions with identical holders (HR,SA ALL):
--     POST /api/skill-taxonomy (HR,SA)              -> talent_skills:CONFIGURE:ALL (same as the
--                                                      talent master-list config it sits beside)
--     TeamGrowthService HR/SA "any employee" override -> talent_skills:VIEW:ALL
--   Self-service endpoints (skill taxonomy GET, my skill ratings, express interest) stay ungated.

-- ============================================================
-- MODULES
-- ============================================================
INSERT INTO modules (code, display_name, custom_role_ready) VALUES
    ('bench',               'Bench Management',        TRUE),
    ('compensation',        'Compensation Bands',      TRUE),
    ('opportunities',       'Internal Opportunities',  TRUE),
    ('projects',            'Projects',                TRUE),
    ('project_allocations', 'Project Allocations',     TRUE),
    ('skill_ratings',       'Skill Ratings (Manager)', TRUE);

-- ============================================================
-- PERMISSIONS
-- ============================================================
INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('bench',               'VIEW'), ('bench',               'CREATE'), ('bench', 'EDIT'), ('bench', 'CONFIGURE'),
    ('compensation',        'VIEW'), ('compensation',        'CREATE'), ('compensation', 'EDIT'),
    ('opportunities',       'VIEW'), ('opportunities',       'CREATE'), ('opportunities', 'APPROVE'),
    ('projects',            'VIEW'), ('projects',            'CREATE'),
    ('project_allocations', 'VIEW'), ('project_allocations', 'CREATE'),
    ('skill_ratings',       'VIEW'), ('skill_ratings',       'EDIT')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code;

-- ============================================================
-- ROLE_PERMISSIONS  (SUPER_ADMIN rows already exist via V233's trigger — listed for readability)
-- ============================================================
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    -- bench
    ('MANAGER',     'bench', 'VIEW', 'TEAM'),
    ('HR_ADMIN',    'bench', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'bench', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'bench', 'EDIT', 'ALL'),
    ('HR_ADMIN',    'bench', 'CONFIGURE', 'ALL'),
    ('SUPER_ADMIN', 'bench', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'bench', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'bench', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'bench', 'CONFIGURE', 'ALL'),

    -- compensation
    ('HR_ADMIN',    'compensation', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'compensation', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'compensation', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'compensation', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'compensation', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'compensation', 'EDIT', 'ALL'),

    -- opportunities
    ('MANAGER',     'opportunities', 'VIEW', 'ALL'),
    ('MANAGER',     'opportunities', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'opportunities', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'opportunities', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'opportunities', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'opportunities', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'opportunities', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'opportunities', 'APPROVE', 'ALL'),

    -- projects
    ('HR_ADMIN',    'projects', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'projects', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'projects', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'projects', 'CREATE', 'ALL'),

    -- project_allocations
    ('MANAGER',     'project_allocations', 'VIEW', 'TEAM'),
    ('HR_ADMIN',    'project_allocations', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'project_allocations', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'project_allocations', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'project_allocations', 'CREATE', 'ALL'),

    -- skill_ratings
    ('MANAGER',     'skill_ratings', 'VIEW', 'TEAM'),
    ('MANAGER',     'skill_ratings', 'EDIT', 'TEAM'),
    ('HR_ADMIN',    'skill_ratings', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'skill_ratings', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'skill_ratings', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'skill_ratings', 'EDIT', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;
