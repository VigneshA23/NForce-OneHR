-- NForce OneHR — Flyway Migration V227
-- Drops the 4 can_* columns on roles (can_manage_employee_records, can_promote_users,
-- can_reset_any_password, can_deactivate_users) — confirmed dead since V224 (see that
-- migration's own header), deliberately deferred until every hasRole/hasAnyRole call site was
-- migrated to hasAuthority(module:ACTION:SCOPE) against role_permissions. That migration
-- (Phase 3, V224-226) is complete — nothing in application code has read these columns at any
-- point in this repo's history; Role.java's own fields for them are removed in this same change.
ALTER TABLE roles
    DROP COLUMN can_manage_employee_records,
    DROP COLUMN can_promote_users,
    DROP COLUMN can_reset_any_password,
    DROP COLUMN can_deactivate_users;
