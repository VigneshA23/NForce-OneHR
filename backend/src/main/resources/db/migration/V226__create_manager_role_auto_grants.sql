-- NForce OneHR — Flyway Migration V226
-- Tracks which users currently hold the MANAGER role because the permission system granted it
-- automatically (first direct report gained), as opposed to a Super Admin manually picking
-- "Manager" in User Management's Role dropdown (a pre-existing, independent capability that
-- works regardless of actual reporting lines — see UserManagementController/Service).
--
-- A row present for a user means "the system granted MANAGER here, and may auto-revoke it";
-- absent means "whatever MANAGER status this user has, if any, is not ours to remove" (either
-- they never held it, or an admin assigned it manually). This is a marker, not a join table —
-- it has no FK to roles, since it only ever concerns the one MANAGER role.
CREATE TABLE manager_role_auto_grants (
    user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
