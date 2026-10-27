-- NForce OneHR — Flyway Migration V225
-- Default permission matrix — reproduces today's access exactly (plus 2 documented fixes
-- confirmed with the user; see below), seeded for the 4 phase-1 roles.
--
-- SCOPE RULE (important for Phase 2 — authority loading): a role_permissions row's scope is
-- the MINIMUM scope that satisfies it. When a user's effective authorities are computed, a
-- scope of ALL must grant the authority for OWN+TEAM+DEPARTMENT+LOCATION+ALL simultaneously
-- (every narrower scope too), TEAM must grant OWN+TEAM, etc. — i.e. authorities cascade
-- downward. This is what makes a plain hasAuthority('module:ACTION:TEAM') check correct for
-- an endpoint that requires "at least TEAM" — a HR_ADMIN whose resolved scope is ALL still
-- passes it, because ALL cascaded down through TEAM. Do not grant only the single exact scope
-- level — that would make an ALL-scoped admin fail a TEAM-level check.
--
-- WHAT IS DELIBERATELY *NOT* SEEDED HERE, AND WHY:
-- Many endpoints have NO @PreAuthorize today (self-service: check-in, submit-my-leave, my
-- skill requests, leave-types/expense-categories/org-structure GETs, etc.) — they rely purely
-- on the blanket `.anyRequest().authenticated()` plus the query itself being hardcoded to the
-- caller's own identity, so there is no role distinction to reproduce. These are intentionally
-- left out of role_permissions; Phase 3 (call-site migration) must NOT add a new hasAuthority
-- check to an endpoint that had none before — that would be a regression, not parity. EMPLOYEE
-- therefore has ZERO rows below: its access is the unconditional self-service floor everyone
-- already has by virtue of being authenticated, not a grant from this table. (Every MANAGER/
-- HR_ADMIN/SUPER_ADMIN user is ALSO always assigned EMPLOYEE — see UserManagementService
-- .rolesFor — so they keep that same unconditional floor too; nothing below needs to restate it.)
--
-- DECISIONS CONFIRMED WITH THE USER BEFORE WRITING THIS SEED (2026-10-12):
-- 1. attendance /team* family + ALL of employee_assignments: today HR_ADMIN/SUPER_ADMIN are
--    bugged down to TEAM scope (same class of bug ReportsService's own doc comment describes
--    being fixed for attendance-request reports) — FIXED here: HR_ADMIN/SUPER_ADMIN get ALL.
-- 2. audit: kept exactly as designed — OWN scope for every role including SUPER_ADMIN ("a
--    personal activity history, not a system-wide trail" per AuditQueryService's own comment).
--    Only which action *categories* are visible widens by role (HR_OPERATIONAL vs +ACCESS_
--    CONTROL for Super Admin) — that stays separate application logic, not expressed as scope.
-- 3. regularization: modeled as single-stage (Manager/HR/SA all reach APPROVE directly) — the
--    two-stage PARTIALLY_APPROVED language in RegularizationService is legacy/dead for new
--    requests; only still reachable for old rows already stuck in that status.
--
-- OTHER NOTED EXCEPTIONS — real business logic Phase 3 must preserve ALONGSIDE (not replace by)
-- the generic permission check; none of these reduce cleanly to a static scope and none needed
-- a seed-data decision, just code-level care when each call site is actually migrated:
--  - regularization: GET /all is SUPER_ADMIN-only (HR_ADMIN excluded), narrower than the
--    general regularization:VIEW:ALL grant below — keep that one endpoint's extra restriction.
--  - leave approve/reject: if the leave belongs to a SUPER_ADMIN employee, routing overrides to
--    that person's own current manager (or themselves with no manager) — HR_ADMIN/SUPER_ADMIN's
--    normal approve bypass does NOT apply to that one case.
--  - expenses final-stage approve/reject/mark-paid: gated per-claim by the Workflow-Studio-
--    configured dynamic stage chain (canActAtFinalStage) — the permission below grants
--    *eligibility*, the per-claim chain still decides whether this claim needs this approver.
--  - "Peers" views (attendance/peers, attendance-requests/peers/wfh, leave/peers): no existing
--    role gate at all today (any authenticated user, self-initiated) — correctly un-seeded,
--    same as any other self-service endpoint.

-- ============================================================
-- MODULES
-- ============================================================
INSERT INTO modules (code, display_name) VALUES
    ('attendance',          'Attendance'),
    ('regularization',      'Regularization'),
    ('attendance_requests', 'Attendance Requests (WFH / Partial Day)'),
    ('overtime',            'Overtime'),
    ('leave',               'Leave'),
    ('expenses',            'Expenses'),
    ('organization',        'Organization Structure'),
    ('organization_shifts', 'Shifts & Weekly-Off Policies'),
    ('documents',           'Documents & Compliance'),
    ('announcements',       'Announcements'),
    ('holidays',            'Holidays'),
    ('policies',            'Policies'),
    ('talent_skills',       'Talent & Skills'),
    ('ai_assistant',        'AI Assistant Administration'),
    ('employees',           'Employee Master'),
    ('employee_assignments','Employee Shift/Weekly-Off Assignments'),
    ('penalization',        'Penalization Policy'),
    ('onboarding',          'Onboarding'),
    ('helpdesk',            'HR Helpdesk'),
    ('hr_help_content',     'HR Help Content'),
    ('exceptions',          'Exception Dashboard'),
    ('audit',               'Audit Log'),
    ('approval_rules',      'Approval Rules (Workflow Studio)'),
    ('reports',             'Reports & Analytics'),
    ('user_management',     'User Management');

-- ============================================================
-- PERMISSIONS  (module x action pairs that actually have a role-gated endpoint today)
-- ============================================================
INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('attendance',          'VIEW'),
    ('regularization',      'VIEW'), ('regularization',      'APPROVE'),
    ('attendance_requests', 'VIEW'), ('attendance_requests', 'APPROVE'),
    ('overtime',            'VIEW'), ('overtime',            'APPROVE'),
    ('leave',               'VIEW'), ('leave',               'APPROVE'), ('leave',               'CONFIGURE'),
    ('expenses',            'VIEW'), ('expenses',            'APPROVE'), ('expenses',            'CONFIGURE'),
    ('organization',        'CREATE'), ('organization',      'EDIT'), ('organization',          'DELETE'),
    ('organization_shifts', 'CREATE'), ('organization_shifts','EDIT'), ('organization_shifts',   'DELETE'), ('organization_shifts', 'CONFIGURE'),
    ('documents',           'VIEW'), ('documents',           'APPROVE'), ('documents',           'EXPORT'), ('documents', 'CONFIGURE'),
    ('announcements',       'VIEW'), ('announcements',       'CREATE'), ('announcements',       'EDIT'), ('announcements', 'DELETE'),
    ('holidays',            'VIEW'), ('holidays',            'CREATE'), ('holidays',            'EDIT'), ('holidays', 'DELETE'),
    ('policies',            'VIEW'), ('policies',            'CREATE'), ('policies',            'EDIT'), ('policies', 'DELETE'),
    ('talent_skills',       'VIEW'), ('talent_skills',       'CREATE'), ('talent_skills',       'APPROVE'), ('talent_skills', 'EXPORT'), ('talent_skills', 'CONFIGURE'),
    ('ai_assistant',        'VIEW'), ('ai_assistant',        'CONFIGURE'),
    ('employees',           'VIEW'), ('employees',           'CREATE'), ('employees',           'EDIT'),
    ('employee_assignments','VIEW'), ('employee_assignments','CREATE'), ('employee_assignments','EDIT'),
    ('penalization',        'VIEW'), ('penalization',        'CREATE'), ('penalization',        'EDIT'), ('penalization', 'DELETE'),
    ('onboarding',          'VIEW'), ('onboarding',          'CREATE'), ('onboarding',          'EDIT'), ('onboarding', 'APPROVE'),
    ('helpdesk',            'VIEW'), ('helpdesk',            'CREATE'), ('helpdesk',            'EDIT'),
    ('hr_help_content',     'VIEW'), ('hr_help_content',     'CREATE'), ('hr_help_content',     'EDIT'), ('hr_help_content', 'DELETE'),
    ('exceptions',          'VIEW'),
    ('audit',               'VIEW'), ('audit',               'EXPORT'),
    ('approval_rules',      'VIEW'), ('approval_rules',      'CREATE'), ('approval_rules',      'EDIT'), ('approval_rules', 'DELETE'), ('approval_rules', 'CONFIGURE'),
    ('reports',             'VIEW'), ('reports',             'EXPORT'),
    ('user_management',     'VIEW'), ('user_management',     'CREATE'), ('user_management',     'EDIT'), ('user_management', 'DELETE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code;

-- ============================================================
-- ROLE_PERMISSIONS
-- ============================================================
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    -- attendance — Manager stays TEAM (true today); HR/SA fixed to ALL (decision #1)
    ('MANAGER',     'attendance', 'VIEW', 'TEAM'),
    ('HR_ADMIN',    'attendance', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'attendance', 'VIEW', 'ALL'),

    -- regularization — single-stage (decision #3); GET /all's extra SUPER_ADMIN-only
    -- narrowing is a Phase-3 code-level exception on top of this, not a different seed row
    ('MANAGER',     'regularization', 'VIEW', 'TEAM'),
    ('MANAGER',     'regularization', 'APPROVE', 'TEAM'),
    ('HR_ADMIN',    'regularization', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'regularization', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'regularization', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'regularization', 'APPROVE', 'ALL'),

    -- attendance_requests (WFH / Partial Day)
    ('MANAGER',     'attendance_requests', 'VIEW', 'TEAM'),
    ('MANAGER',     'attendance_requests', 'APPROVE', 'TEAM'),
    ('HR_ADMIN',    'attendance_requests', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'attendance_requests', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'attendance_requests', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'attendance_requests', 'APPROVE', 'ALL'),

    -- overtime
    ('MANAGER',     'overtime', 'VIEW', 'TEAM'),
    ('MANAGER',     'overtime', 'APPROVE', 'TEAM'),
    ('HR_ADMIN',    'overtime', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'overtime', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'overtime', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'overtime', 'APPROVE', 'ALL'),

    -- leave — approve/reject's Super-Admin-as-employee routing override is a Phase-3
    -- code-level exception on top of this, not a different seed row
    ('MANAGER',     'leave', 'VIEW', 'TEAM'),
    ('MANAGER',     'leave', 'APPROVE', 'TEAM'),
    ('HR_ADMIN',    'leave', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'leave', 'APPROVE', 'ALL'),
    ('HR_ADMIN',    'leave', 'CONFIGURE', 'ALL'),
    ('SUPER_ADMIN', 'leave', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'leave', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'leave', 'CONFIGURE', 'ALL'),

    -- expenses — final-stage approve is further gated per-claim by the dynamic Workflow-Studio
    -- stage chain (canActAtFinalStage) — this grants eligibility, not an unconditional pass
    ('MANAGER',     'expenses', 'VIEW', 'TEAM'),
    ('MANAGER',     'expenses', 'APPROVE', 'TEAM'),
    ('HR_ADMIN',    'expenses', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'expenses', 'APPROVE', 'ALL'),
    ('HR_ADMIN',    'expenses', 'CONFIGURE', 'ALL'),
    ('SUPER_ADMIN', 'expenses', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'expenses', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'expenses', 'CONFIGURE', 'ALL'),

    -- organization (departments / designations / locations / business units)
    ('HR_ADMIN',    'organization', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'organization', 'EDIT', 'ALL'),
    ('HR_ADMIN',    'organization', 'DELETE', 'ALL'),
    ('SUPER_ADMIN', 'organization', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'organization', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'organization', 'DELETE', 'ALL'),

    -- organization_shifts (shifts / weekly-off policies / shift-weekly-off rules / attendance
    -- rules) — deliberately SUPER_ADMIN-only; HR_ADMIN does NOT get this one (confirmed
    -- narrower-than-usual gate, preserved exactly)
    ('SUPER_ADMIN', 'organization_shifts', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'organization_shifts', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'organization_shifts', 'DELETE', 'ALL'),
    ('SUPER_ADMIN', 'organization_shifts', 'CONFIGURE', 'ALL'),

    -- documents (employee document compliance administration; document-types CRUD folds into
    -- CONFIGURE)
    ('HR_ADMIN',    'documents', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'documents', 'APPROVE', 'ALL'),
    ('HR_ADMIN',    'documents', 'EXPORT', 'ALL'),
    ('HR_ADMIN',    'documents', 'CONFIGURE', 'ALL'),
    ('SUPER_ADMIN', 'documents', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'documents', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'documents', 'EXPORT', 'ALL'),
    ('SUPER_ADMIN', 'documents', 'CONFIGURE', 'ALL'),

    -- announcements
    ('HR_ADMIN',    'announcements', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'announcements', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'announcements', 'EDIT', 'ALL'),
    ('HR_ADMIN',    'announcements', 'DELETE', 'ALL'),
    ('SUPER_ADMIN', 'announcements', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'announcements', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'announcements', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'announcements', 'DELETE', 'ALL'),

    -- holidays (the admin listing/CRUD — distinct from the ungated /my-location self-service GET)
    ('HR_ADMIN',    'holidays', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'holidays', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'holidays', 'EDIT', 'ALL'),
    ('HR_ADMIN',    'holidays', 'DELETE', 'ALL'),
    ('SUPER_ADMIN', 'holidays', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'holidays', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'holidays', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'holidays', 'DELETE', 'ALL'),

    -- policies (publish lifecycle: publish/versioning/deactivate/reactivate/reset/remind/
    -- attachment fold into EDIT)
    ('HR_ADMIN',    'policies', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'policies', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'policies', 'EDIT', 'ALL'),
    ('HR_ADMIN',    'policies', 'DELETE', 'ALL'),
    ('SUPER_ADMIN', 'policies', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'policies', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'policies', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'policies', 'DELETE', 'ALL'),

    -- talent_skills (master-list CRUD/merge/alias/reorder + skill-request review fold into
    -- CONFIGURE/APPROVE; dashboard/search/directory/migration-candidates = VIEW;
    -- directory export = EXPORT; migration scan = CREATE)
    ('HR_ADMIN',    'talent_skills', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'talent_skills', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'talent_skills', 'APPROVE', 'ALL'),
    ('HR_ADMIN',    'talent_skills', 'EXPORT', 'ALL'),
    ('HR_ADMIN',    'talent_skills', 'CONFIGURE', 'ALL'),
    ('SUPER_ADMIN', 'talent_skills', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'talent_skills', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'talent_skills', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'talent_skills', 'EXPORT', 'ALL'),
    ('SUPER_ADMIN', 'talent_skills', 'CONFIGURE', 'ALL'),

    -- ai_assistant — deliberately SUPER_ADMIN-only; HR_ADMIN does NOT get this one (confirmed)
    ('SUPER_ADMIN', 'ai_assistant', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'ai_assistant', 'CONFIGURE', 'ALL'),

    -- employees — CREATE (new employee accounts) is SUPER_ADMIN-only; HR_ADMIN does NOT get it
    ('HR_ADMIN',    'employees', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'employees', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'employees', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'employees', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'employees', 'CREATE', 'ALL'),

    -- employee_assignments — Manager stays TEAM (true today); HR/SA fixed to ALL (decision #1)
    ('MANAGER',     'employee_assignments', 'VIEW', 'TEAM'),
    ('MANAGER',     'employee_assignments', 'CREATE', 'TEAM'),
    ('MANAGER',     'employee_assignments', 'EDIT', 'TEAM'),
    ('HR_ADMIN',    'employee_assignments', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'employee_assignments', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'employee_assignments', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'employee_assignments', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'employee_assignments', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'employee_assignments', 'EDIT', 'ALL'),

    -- penalization (policy document/versions + allocation CRUD; my-current self-service lookup
    -- stays ungated, same as any other self-service endpoint)
    ('HR_ADMIN',    'penalization', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'penalization', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'penalization', 'EDIT', 'ALL'),
    ('HR_ADMIN',    'penalization', 'DELETE', 'ALL'),
    ('SUPER_ADMIN', 'penalization', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'penalization', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'penalization', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'penalization', 'DELETE', 'ALL'),

    -- onboarding
    ('HR_ADMIN',    'onboarding', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'onboarding', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'onboarding', 'EDIT', 'ALL'),
    ('HR_ADMIN',    'onboarding', 'APPROVE', 'ALL'),
    ('SUPER_ADMIN', 'onboarding', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'onboarding', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'onboarding', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'onboarding', 'APPROVE', 'ALL'),

    -- helpdesk (HR admin queue only — the employee-facing ticket-submission side is a separate
    -- controller not yet investigated; flagged for Phase 3, not seeded here)
    ('HR_ADMIN',    'helpdesk', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'helpdesk', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'helpdesk', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'helpdesk', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'helpdesk', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'helpdesk', 'EDIT', 'ALL'),

    -- hr_help_content (authoring only — the submitted-content approval workflow is a separate
    -- controller, often resolved to a plain Manager approver; flagged for Phase 3, not seeded here)
    ('HR_ADMIN',    'hr_help_content', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'hr_help_content', 'CREATE', 'ALL'),
    ('HR_ADMIN',    'hr_help_content', 'EDIT', 'ALL'),
    ('HR_ADMIN',    'hr_help_content', 'DELETE', 'ALL'),
    ('SUPER_ADMIN', 'hr_help_content', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'hr_help_content', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'hr_help_content', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'hr_help_content', 'DELETE', 'ALL'),

    -- exceptions — no EMPLOYEE access at all today (plain employees get 403)
    ('MANAGER',     'exceptions', 'VIEW', 'TEAM'),
    ('HR_ADMIN',    'exceptions', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'exceptions', 'VIEW', 'ALL'),

    -- audit — OWN for every role by design (decision #2); action-category widening by role
    -- stays separate application logic, not expressed as scope here
    ('MANAGER',     'audit', 'VIEW', 'OWN'),
    ('MANAGER',     'audit', 'EXPORT', 'OWN'),
    ('HR_ADMIN',    'audit', 'VIEW', 'OWN'),
    ('HR_ADMIN',    'audit', 'EXPORT', 'OWN'),
    ('SUPER_ADMIN', 'audit', 'VIEW', 'OWN'),
    ('SUPER_ADMIN', 'audit', 'EXPORT', 'OWN'),

    -- approval_rules (Workflow Studio) — deliberately SUPER_ADMIN-only; HR_ADMIN does NOT get
    -- this one ("per the story's explicit authorization requirement" per the existing code comment)
    ('SUPER_ADMIN', 'approval_rules', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'approval_rules', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'approval_rules', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'approval_rules', 'DELETE', 'ALL'),
    ('SUPER_ADMIN', 'approval_rules', 'CONFIGURE', 'ALL'),

    -- reports — already correctly scoped today (ReportsService's own doc comment records the
    -- bug-fix history for this one)
    ('MANAGER',     'reports', 'VIEW', 'TEAM'),
    ('MANAGER',     'reports', 'EXPORT', 'TEAM'),
    ('HR_ADMIN',    'reports', 'VIEW', 'ALL'),
    ('HR_ADMIN',    'reports', 'EXPORT', 'ALL'),
    ('SUPER_ADMIN', 'reports', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'reports', 'EXPORT', 'ALL'),

    -- user_management — deliberately SUPER_ADMIN-only; HR_ADMIN has zero access here (distinct
    -- surface from employees:EDIT above, which HR_ADMIN does hold — confirmed not an
    -- inconsistency to "fix")
    ('SUPER_ADMIN', 'user_management', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'user_management', 'CREATE', 'ALL'),
    ('SUPER_ADMIN', 'user_management', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'user_management', 'DELETE', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action;
