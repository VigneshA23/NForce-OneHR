-- NForce OneHR — Flyway Migration V2
-- Bootstrap: create the first Super Admin so someone can log in on day one
-- and create every other account (HR Admin, Manager, Employee) from the UI.
--
-- SECURITY: This password is a PLACEHOLDER for local/dev environments only.
-- Never run this as-is against a real/staging/prod database — generate a
-- fresh bcrypt hash for a real secret there instead.

INSERT INTO users (id, email, password_hash, email_verified_at, is_active)
VALUES (
    gen_random_uuid(),
    'superadmin@nforceone.com',
    '$2b$10$LxaD8jQMsa8UGDvF4uiIwOvGJFcss7qLGGdChmxhvfiL7f9ylIxje', -- bcrypt hash of: ChangeMe@OneHR2026
    now(),
    TRUE
);

INSERT INTO user_roles (user_id, role_id, granted_by, granted_at)
SELECT u.id, r.id, NULL, now()
FROM users u, roles r
WHERE u.email = 'superadmin@nforceone.com'
  AND r.code  = 'SUPER_ADMIN';

ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE users SET must_change_password = TRUE WHERE email = 'superadmin@nforceone.com';
