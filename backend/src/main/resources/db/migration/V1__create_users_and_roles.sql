-- NForce OneHR — Flyway Migration V1
-- Users, Roles, and Role Assignment schema
--
-- Design notes (do not delete this comment block — it records the "why"):
-- 1. Roles are a JOIN TABLE (user_roles), not a single enum column on users,
--    because the master spec allows one person to hold more than one role.
-- 2. Full 7-role set is seeded now (Employee, Manager, HR Admin, Super Admin,
--    Delivery, Finance, Leadership) even though Phase 1 only BUILDS UI for
--    the first 4 — this matches "scaffold all 7 nav structures now."
-- 3. Naming: "SUPER_ADMIN", not "ADMIN" — unified with Sync's naming per spec.
-- 4. can_promote_users / can_reset_any_password are NOT derived from role
--    name in application code alone — they're explicit boolean columns on
--    the role definition table, so the permission is auditable and
--    changeable via a future migration without a code deploy.
-- 5. Never run ad hoc DDL/DML against Neon directly. Every future change to
--    this schema is a new VN__*.sql file, committed to git.

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto; -- for gen_random_uuid()

-- ============================================================
-- ROLE DEFINITIONS
-- ============================================================
CREATE TABLE roles (
    id                      SMALLSERIAL PRIMARY KEY,
    code                    VARCHAR(30)  NOT NULL UNIQUE,   -- e.g. 'SUPER_ADMIN'
    display_name            VARCHAR(50)  NOT NULL,          -- e.g. 'Super Admin'
    can_manage_employee_records BOOLEAN  NOT NULL DEFAULT FALSE,
    can_promote_users       BOOLEAN      NOT NULL DEFAULT FALSE,
    can_reset_any_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    can_deactivate_users    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_phase1               BOOLEAN      NOT NULL DEFAULT FALSE, -- true = Employee/Manager/HR Admin/Super Admin
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO roles (code, display_name, can_manage_employee_records, can_promote_users, can_reset_any_password, can_deactivate_users, is_phase1) VALUES
    ('EMPLOYEE',   'Employee',   FALSE, FALSE, FALSE, FALSE, TRUE),
    ('MANAGER',    'Manager',    FALSE, FALSE, FALSE, FALSE, TRUE),
    ('HR_ADMIN',   'HR Admin',   TRUE,  FALSE, FALSE, FALSE, TRUE),
    ('SUPER_ADMIN','Super Admin',TRUE,  TRUE,  TRUE,  TRUE,  TRUE),
    ('DELIVERY',   'Delivery',   FALSE, FALSE, FALSE, FALSE, FALSE),
    ('FINANCE',    'Finance',    FALSE, FALSE, FALSE, FALSE, FALSE),
    ('LEADERSHIP', 'Leadership', FALSE, FALSE, FALSE, FALSE, FALSE);

-- ============================================================
-- USERS  (auth identity — separate from richer "employee" HR profile
-- that Employee Master will own; kept minimal here on purpose)
-- ============================================================
CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               CITEXT       NOT NULL UNIQUE,   -- case-insensitive match
    password_hash       VARCHAR(255) NOT NULL,          -- bcrypt
    email_verified_at   TIMESTAMPTZ,                    -- null until verification link clicked
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE, -- deactivate, never delete
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Allowed company email domain(s) enforced at application layer at signup,
-- not as a DB constraint (domain list may change without a schema migration).

-- ============================================================
-- USER <-> ROLE  (many-to-many: a person may hold more than one role)
-- ============================================================
CREATE TABLE user_roles (
    user_id     UUID     NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id     SMALLINT NOT NULL REFERENCES roles(id),
    granted_by  UUID     REFERENCES users(id),           -- who assigned this role (audit)
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

-- ============================================================
-- EMAIL VERIFICATION TOKENS  (time-limited, single-use)
-- ============================================================
CREATE TABLE email_verification_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,   -- store hash of token, not raw token
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_email_verif_user ON email_verification_tokens(user_id);

-- ============================================================
-- PASSWORD RESET TOKENS  (single-use, 1 hour expiry)
-- ============================================================
CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_pw_reset_user ON password_reset_tokens(user_id);

-- ============================================================
-- AUDIT LOG  (every sensitive action: actor, timestamp, before/after —
-- per master doc "Core rules". Auth-relevant events land here too:
-- role grants, admin-initiated password resets, deactivations.)
-- ============================================================
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID REFERENCES users(id),
    action      VARCHAR(60)  NOT NULL,   -- e.g. 'ROLE_GRANTED', 'PASSWORD_RESET_BY_ADMIN', 'USER_DEACTIVATED'
    target_id   UUID,
    before_state JSONB,
    after_state  JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_actor  ON audit_log(actor_id);
CREATE INDEX idx_audit_target ON audit_log(target_id);
CREATE INDEX idx_audit_action ON audit_log(action);
