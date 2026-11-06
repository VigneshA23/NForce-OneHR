-- NForce OneHR — Flyway Migration V233
-- Invariant: SUPER_ADMIN holds every registered permission.
--
-- Roles & Permissions' privilege ceiling (an admin can only grant/assign/manage what they hold —
-- see EffectivePermissionService#assertWithinCeiling) relies on Super Admin's coverage being the
-- superset of every other role's. Without this, a later migration that registers a permission and
-- grants it only to, say, HR_ADMIN would silently stop Super Admins from managing those HR users.
-- Enforced in the database, so it holds for every future migration without anyone remembering it:
--   1. backfill: any registered permission Super Admin lacks is granted at ALL (no-op today);
--   2. trigger: every newly inserted permission is granted to Super Admin at ALL immediately.
-- Super Admin's explicit narrower rows (audit:*:OWN by design, see V225) are left as they are.
-- Migrations seeding new permissions should still list SUPER_ADMIN explicitly for readability and
-- use ON CONFLICT (role_id, permission_id) DO NOTHING, since this trigger got there first.
--
-- Rollback: DROP TRIGGER trg_super_admin_holds_new_permission ON permissions;
--           DROP FUNCTION grant_new_permission_to_super_admin();
--           DROP TRIGGER trg_super_admin_grant_upsert ON role_permissions;
--           DROP FUNCTION super_admin_grant_upsert();

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'SUPER_ADMIN'
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

CREATE OR REPLACE FUNCTION grant_new_permission_to_super_admin() RETURNS trigger AS $$
BEGIN
    INSERT INTO role_permissions (role_id, permission_id, scope)
    SELECT r.id, NEW.id, 'ALL' FROM roles r WHERE r.code = 'SUPER_ADMIN'
    ON CONFLICT (role_id, permission_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_super_admin_holds_new_permission
    AFTER INSERT ON permissions
    FOR EACH ROW EXECUTE FUNCTION grant_new_permission_to_super_admin();

-- The trigger above means a later migration that registers a permission and then inserts its own
-- SUPER_ADMIN row (as V225 did) would hit uq_role_permission — and on this shared dev database
-- other branches' migrations may not know about the trigger. So a SUPER_ADMIN insert for a
-- (role, permission) pair that already exists becomes "set its scope" instead of failing, keeping
-- whatever scope the migration intended (e.g. audit's OWN). Other roles keep the normal unique error.
CREATE OR REPLACE FUNCTION super_admin_grant_upsert() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM roles r WHERE r.id = NEW.role_id AND r.code = 'SUPER_ADMIN')
       AND EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = NEW.role_id AND rp.permission_id = NEW.permission_id) THEN
        UPDATE role_permissions SET scope = NEW.scope
        WHERE role_id = NEW.role_id AND permission_id = NEW.permission_id;
        RETURN NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_super_admin_grant_upsert
    BEFORE INSERT ON role_permissions
    FOR EACH ROW EXECUTE FUNCTION super_admin_grant_upsert();
