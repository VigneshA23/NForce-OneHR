-- NForce OneHR — Flyway Migration V109
-- Tracks consecutive failed login attempts per user so AuthService can lock an
-- account for 4 hours after 7 consecutive wrong-password attempts.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS failed_login_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;
