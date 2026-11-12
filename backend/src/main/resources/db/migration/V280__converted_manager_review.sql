-- Roles & Permissions enhancement, Phase 3 — administrative review of converted Manager assignments.
--
-- V269 kept every automatically-granted MANAGER role as an explicit assignment and snapshotted the old
-- marker rows into manager_role_auto_grants_archive for review. This adds the two columns an
-- administrator needs to record "I looked at this person and decided" from Roles & Permissions.
-- No user_roles / role_permissions row is touched. Additive and idempotent.
-- Rollback: ALTER TABLE manager_role_auto_grants_archive DROP COLUMN reviewed_at, DROP COLUMN reviewed_by;
ALTER TABLE manager_role_auto_grants_archive
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS reviewed_by UUID NULL REFERENCES users(id);
