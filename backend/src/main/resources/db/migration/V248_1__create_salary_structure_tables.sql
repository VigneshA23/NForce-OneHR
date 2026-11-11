-- NForce OneHR — Flyway Migration V247
-- US-C01 (ONEHR-607) — Payroll Epic (ONEHR-579), Phase 1: configurable Salary Structure
-- template. Foundational — no upstream dependency; every later payroll story reads this data.
--
-- Numbered V247, not V232: by the time this was written, V232-V246 were already applied to the
-- shared dev DB from an unmerged branch (origin/aanuj, "roles and permissions admin" — a custom-
-- roles overhaul, not yet in dev/vyshnavi) plus dev's own V243-246. Always check
-- flyway_schema_history on the shared DB itself before claiming a version, not just local files
-- or even `dev` — a version can be live from a branch nobody has merged yet.
--
-- IDEMPOTENT ON PURPOSE: this exact content was already applied to the shared dev database as V247
-- (V247 is kept as deployed; this file re-runs it as V248.1 for environments that never had it), so every
-- statement below is safe to run when the objects already exist (IF NOT EXISTS / ON CONFLICT DO NOTHING).
-- Nothing here changes data or structure that is already in place.
--
-- ============================================================
-- SALARY STRUCTURES  (a reusable pay-component template, e.g. per grade)
-- ============================================================
CREATE TABLE IF NOT EXISTS salary_structures (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,
    grade           VARCHAR(50),
    created_at      TIMESTAMP    NOT NULL DEFAULT now()
);

-- ============================================================
-- SALARY COMPONENTS  (Basic, HRA, allowances, deductions — one row per component on a structure)
-- ============================================================
CREATE TABLE IF NOT EXISTS salary_components (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    structure_id    UUID          NOT NULL REFERENCES salary_structures(id),
    name            VARCHAR(50)   NOT NULL,
    type            VARCHAR(20)   NOT NULL,
    calc_type       VARCHAR(20)   NOT NULL,
    value           NUMERIC(12,2) NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT now(),
    CONSTRAINT chk_salary_components_type CHECK (type IN ('EARNING', 'DEDUCTION')),
    CONSTRAINT chk_salary_components_calc_type CHECK (calc_type IN ('FIXED', 'PERCENT_OF_BASIC')),
    CONSTRAINT chk_salary_components_value_non_negative CHECK (value >= 0)
);
CREATE INDEX IF NOT EXISTS idx_salary_components_structure ON salary_components(structure_id);

-- ============================================================
-- PERMISSION MATRIX — new 'payroll' module (see V224/V225 for the module x action x scope
-- model this extends). Additive only: no existing module/permission/role_permissions row is
-- touched. HR_ADMIN/SUPER_ADMIN only for now — Phase 1 has no Manager- or Employee-facing
-- payroll endpoint yet.
--
-- SUPER_ADMIN is listed explicitly below for readability even though origin/aanuj's (unmerged)
-- V233 trigger already auto-grants every newly inserted permission to SUPER_ADMIN at ALL scope
-- the moment the `permissions` row above is created — ON CONFLICT DO NOTHING is required here
-- per that migration's own documented convention, since the trigger beats this INSERT to it.
-- ============================================================
INSERT INTO modules (code, display_name) VALUES
    ('payroll', 'Payroll & Compensation')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES ('payroll', 'VIEW'), ('payroll', 'CONFIGURE')) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code
ON CONFLICT (module_id, action) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('HR_ADMIN',    'payroll', 'VIEW',      'ALL'),
    ('HR_ADMIN',    'payroll', 'CONFIGURE', 'ALL'),
    ('SUPER_ADMIN', 'payroll', 'VIEW',      'ALL'),
    ('SUPER_ADMIN', 'payroll', 'CONFIGURE', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;
