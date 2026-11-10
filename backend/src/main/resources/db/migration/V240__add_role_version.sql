-- NForce OneHR — Flyway Migration V240
-- Optimistic-lock version for roles (Role.version, JPA @Version): Roles & Permissions sends back
-- the version it loaded, so when two admins edit the same role the second, stale save is rejected
-- with a "changed by someone else — reload" error instead of silently overwriting the first.
-- Additive; existing rows start at 0. Rollback: ALTER TABLE roles DROP COLUMN version;
ALTER TABLE roles ADD COLUMN version INT NOT NULL DEFAULT 0;
