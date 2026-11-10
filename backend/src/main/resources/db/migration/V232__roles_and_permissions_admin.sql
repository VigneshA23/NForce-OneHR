-- NForce OneHR — Flyway Migration V232
-- Roles & Permissions administration: custom roles, role status, and the permissions that govern
-- the Roles & Permissions module itself. Purely additive — no existing row's meaning changes:
--   * all 7 existing roles become is_system = TRUE (custom roles created later default to FALSE);
--   * every existing role stays active, keeps its code/display_name and every user_roles row;
--   * no existing role_permissions row is touched — SUPER_ADMIN only GAINS the new rows below.
--
-- Recovery: nothing here deletes data. To back out, delete the role_permissions/permissions/
-- modules rows for 'roles' and 'user_roles' (custom roles must be unassigned + deleted first), then
-- drop the added columns/index. Check flyway_schema_history on the shared DB before relying on
-- V232 being free (see V224's header — versions have collided across branches before).

-- ============================================================
-- ROLES: system/custom distinction, description, status
-- ============================================================
ALTER TABLE roles
    ADD COLUMN is_system   BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN description VARCHAR(255),
    ADD COLUMN is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now();

UPDATE roles SET is_system = TRUE;

UPDATE roles SET description = v.description
FROM (VALUES
    ('EMPLOYEE',    'Base role held by every user — self-service features.'),
    ('MANAGER',     'Team-scoped approvals and visibility. Granted automatically when someone gains a direct report.'),
    ('HR_ADMIN',    'Organization-wide HR administration.'),
    ('SUPER_ADMIN', 'Full system access, including user management and roles & permissions. Protected: cannot be edited.')
) AS v(code, description)
WHERE roles.code = v.code;

-- Role names are what admins pick from — "Hr admin" next to "HR Admin" must not be possible.
CREATE UNIQUE INDEX uq_roles_display_name_ci ON roles (lower(display_name));

-- ============================================================
-- MODULES: which modules a role's permissions can actually be configured for
--
-- TRUE only where enforcement is purely hasAuthority(module:ACTION:SCOPE). For the others, the
-- service layer still decides access by role CODE (e.g. LeaveService's HR/SA override,
-- ExpenseService's stage == role code), so a configured grant would not change real behavior —
-- those stay read-only in the editor until their role-code checks are migrated. Onboarding a
-- module later = migrate its checks, then flip this flag in a new migration.
-- ============================================================
ALTER TABLE modules ADD COLUMN custom_role_ready BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO modules (code, display_name, custom_role_ready) VALUES
    ('roles',      'Roles & Permissions',  TRUE),
    ('user_roles', 'User Role Assignment', TRUE);

UPDATE modules SET custom_role_ready = TRUE
WHERE code IN ('organization', 'organization_shifts', 'holidays', 'penalization', 'talent_skills',
               'approval_rules', 'ai_assistant', 'employees', 'employee_assignments', 'user_management');

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('roles', 'VIEW'), ('roles', 'CREATE'), ('roles', 'EDIT'), ('roles', 'DELETE'), ('roles', 'CONFIGURE'),
    ('user_roles', 'VIEW'), ('user_roles', 'EDIT')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code;

-- Only SUPER_ADMIN gets these — the same holders who already had user_management today.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
JOIN permissions p ON TRUE
JOIN modules m ON m.id = p.module_id
WHERE r.code = 'SUPER_ADMIN' AND m.code IN ('roles', 'user_roles');
