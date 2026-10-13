-- NForce OneHR — Flyway Migration V224
-- Configurable permission model: Module x Action x Scope
--
-- Numbered V224 (not V222) because another branch's "bench management" migrations landed
-- V222/V223 on the shared dev database first — see flyway_schema_history before assuming a
-- version is free (this repo's own established convention — see V40's header for precedent).
--
-- Replaces the current hardcoded hasRole/hasAnyRole(...) checks scattered across ~45
-- controllers/services with data-driven authorization: a role's access is the set of
-- (module, action, scope) rows in role_permissions, loaded fresh on every request
-- (same "re-resolve from DB per request" pattern CustomUserDetailsService already uses
-- for ROLE_* authorities — see that class — so a permission change takes effect
-- immediately, no stale-JWT problem to solve).
--
-- This migration is schema-only and purely additive: no existing table is altered, no
-- existing hasRole/hasAnyRole check is touched yet. The actual default matrix (seed
-- data reproducing today's behavior exactly) is a separate follow-up migration, once
-- that extraction is complete — splitting "create the shape" from "fill in the rows"
-- the same way this codebase already does elsewhere (e.g. V216/V217 skill_master before
-- its alias/seed data).
--
-- The 4 now-unused can_* columns on roles (can_manage_employee_records,
-- can_promote_users, can_reset_any_password, can_deactivate_users — confirmed dead:
-- nothing in application code reads them, see Role.java) are deliberately left alone
-- here. Dropping them is a later cleanup migration, once every hasRole/hasAnyRole call
-- site has actually been migrated to hasAuthority(...) against this new model.

-- ============================================================
-- MODULES  (business-level areas, e.g. 'attendance', 'organization')
-- ============================================================
CREATE TABLE modules (
    id              SMALLSERIAL PRIMARY KEY,
    code            VARCHAR(40)  NOT NULL UNIQUE,   -- e.g. 'attendance', 'talent_skills'
    display_name    VARCHAR(80)  NOT NULL,          -- e.g. 'Attendance', 'Talent & Skills'
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- PERMISSIONS  (one row per module x action that actually exists in the app —
-- not every module necessarily has all 8 actions, e.g. most modules have no REVERT)
-- ============================================================
CREATE TABLE permissions (
    id              SERIAL PRIMARY KEY,
    module_id       SMALLINT     NOT NULL REFERENCES modules(id),
    action          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_permission_module_action UNIQUE (module_id, action),
    CONSTRAINT chk_permission_action CHECK (
        action IN ('VIEW','CREATE','EDIT','DELETE','APPROVE','REVERT','EXPORT','CONFIGURE')
    )
);

-- ============================================================
-- ROLE_PERMISSIONS  (a role's grant of one permission, at one scope)
--
-- Scope meaning:
--   OWN        — only the actor's own records
--   TEAM       — the actor's current direct reports (see DirectReportScopeService)
--   DEPARTMENT — everyone in the actor's department
--   LOCATION   — everyone in the actor's location
--   ALL        — org-wide, no filter
--
-- A role has at most one scope per permission (uq_role_permission below) — there is
-- no "widen scope" row stacking at the role level. Where a USER holds multiple roles,
-- the application computes the union of all their roles' permissions and, for any
-- permission granted by more than one held role, takes the widest scope (ALL > LOCATION
-- = DEPARTMENT > TEAM > OWN) — that resolution happens in application code at
-- authority-loading time, not in this table.
-- ============================================================
CREATE TABLE role_permissions (
    id              BIGSERIAL PRIMARY KEY,
    role_id         SMALLINT     NOT NULL REFERENCES roles(id),
    permission_id   INTEGER      NOT NULL REFERENCES permissions(id),
    scope           VARCHAR(20)  NOT NULL,
    granted_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_role_permission UNIQUE (role_id, permission_id),
    CONSTRAINT chk_role_permission_scope CHECK (
        scope IN ('OWN','TEAM','DEPARTMENT','LOCATION','ALL')
    )
);
CREATE INDEX idx_role_permissions_role ON role_permissions(role_id);
CREATE INDEX idx_role_permissions_permission ON role_permissions(permission_id);
