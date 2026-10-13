-- NForce OneHR — Flyway Migration V228
-- Account Owner + Super Admin module fencing (depends on the Module x Action x Scope
-- permission engine from V224-227).
--
-- This app is single-tenant (confirmed: no Organization/Company/Tenant entity, no tenant_id
-- anywhere) — "Account Owner" is modeled as a flag on users, scoped to the one deployment, not
-- a per-tenant concept. Capped at 1-2 holders by application-layer validation (not practical to
-- express as a DB constraint); the bootstrap Super Admin is seeded as the first Account Owner so
-- the system is never left without one to manage fences.
--
-- super_admin_module_fences is deliberately SUBTRACTIVE, a new kind of row for this permission
-- model — everything in role_permissions (V224-227) is purely additive (union of held roles'
-- grants). A fenced (module, action) pair is removed from the FINAL computed authority set for
-- any account that holds SUPER_ADMIN, regardless of which role the grant came from — "the person
-- who controls access cannot also run payroll or author HR policy" applies to the account, not
-- to one specific role it holds. action = '*' fences every action on that module (present and
-- future permissions), matching the user story's own "PAYROLL:*" notation.
--
-- Payroll does not exist as a feature yet (separate epic, ONEHR-579) — 'payroll' is seeded here
-- as a minimal stub module (VIEW/CONFIGURE only) purely so the fence has something real to
-- reference; ONEHR-579 can extend it later. SUPER_ADMIN is granted the stub normally (same
-- blanket-ALL pattern as every other module) specifically so the fence has something real to
-- suppress — the fence is what keeps it from actually being exercised, not the absence of a
-- grant underneath it.
--
-- leave/penalization/policies have no single "CONFIGURE" action in every case (policies and
-- penalization never got one — see V225's header) — those two fence CREATE+EDIT+DELETE as a
-- bundle (the actions that actually gate authoring/publishing) rather than inventing an unused
-- CONFIGURE permission row neither has a real check for.
--
-- HR_ADMIN gaining user_management:VIEW:ALL here is a deliberate, confirmed expansion (see this
-- story's design discussion) — HR previously had zero rows in this module by design (V225).

ALTER TABLE users ADD COLUMN is_account_owner BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE users SET is_account_owner = TRUE WHERE email = 'superadmin@nforceone.com';

CREATE TABLE super_admin_module_fences (
    id          BIGSERIAL PRIMARY KEY,
    module_id   SMALLINT     NOT NULL REFERENCES modules(id),
    action      VARCHAR(20)  NOT NULL,  -- a real Permission.action value, or '*' for the whole module
    fenced_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    fenced_by   UUID         NOT NULL REFERENCES users(id),
    CONSTRAINT uq_fence_module_action UNIQUE (module_id, action),
    CONSTRAINT chk_fence_action CHECK (
        action IN ('VIEW','CREATE','EDIT','DELETE','APPROVE','REVERT','EXPORT','CONFIGURE','*')
    )
);

-- ============================================================
-- Payroll stub module (ONEHR-579 builds the real feature; this just gives the fence a target)
-- ============================================================
INSERT INTO modules (code, display_name) VALUES ('payroll', 'Payroll');

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES ('payroll', 'VIEW'), ('payroll', 'CONFIGURE')) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
JOIN modules m ON m.code = 'payroll'
JOIN permissions p ON p.module_id = m.id
WHERE r.code = 'SUPER_ADMIN';

-- ============================================================
-- HR_ADMIN: new user_management:VIEW:ALL grant (see this migration's header)
-- ============================================================
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
JOIN modules m ON m.code = 'user_management'
JOIN permissions p ON p.module_id = m.id AND p.action = 'VIEW'
WHERE r.code = 'HR_ADMIN';

-- ============================================================
-- Default fences — payroll wholesale, plus the 3 "policy module" authoring actions
-- ============================================================
INSERT INTO super_admin_module_fences (module_id, action, fenced_by)
SELECT m.id, v.action, owner.id
FROM (VALUES
    ('payroll',       '*'),
    ('leave',         'CONFIGURE'),
    ('penalization',  'CREATE'),
    ('penalization',  'EDIT'),
    ('penalization',  'DELETE'),
    ('policies',      'CREATE'),
    ('policies',      'EDIT'),
    ('policies',      'DELETE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code
CROSS JOIN (SELECT id FROM users WHERE email = 'superadmin@nforceone.com') AS owner;
