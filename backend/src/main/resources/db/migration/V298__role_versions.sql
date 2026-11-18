-- Sensitive data protection, Phase 3 — every change to a role is saved as a numbered version, so any
-- earlier state can be restored.
--
-- A version is the role's name, description, active flag and its full list of authorities
-- ("module:ACTION:SCOPE") right after a change. Role history used to be rebuilt from the latest 500
-- ROLE_* audit rows (no restore, and old entries aged out); this table replaces that.
-- Membership (who holds a role) is deliberately NOT part of a version: restoring never adds or
-- removes people. Membership changes stay in the audit log.
--
-- Every existing role gets a BASELINE version 1 holding its current state, so history starts with
-- something to compare against and restore to.
-- Rollback: DROP TABLE role_versions;
CREATE TABLE role_versions (
    id                     BIGSERIAL    PRIMARY KEY,
    role_id                INTEGER      NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    version_no             INTEGER      NOT NULL,
    name                   VARCHAR(50)  NOT NULL,
    description            VARCHAR(255),
    active                 BOOLEAN      NOT NULL,
    permissions            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    change_type            VARCHAR(20)  NOT NULL
                               CHECK (change_type IN ('BASELINE', 'CREATED', 'UPDATED', 'ACTIVATED', 'DEACTIVATED', 'PERMISSIONS', 'RESTORED')),
    actor_id               UUID,
    reason                 VARCHAR(500),
    restored_from_version  INTEGER,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_role_versions_role_no UNIQUE (role_id, version_no)
);
CREATE INDEX idx_role_versions_role ON role_versions(role_id, version_no DESC);

INSERT INTO role_versions (role_id, version_no, name, description, active, permissions, change_type)
SELECT r.id, 1, r.display_name, r.description, r.is_active,
       COALESCE((SELECT jsonb_agg(m.code || ':' || p.action || ':' || rp.scope ORDER BY m.code, p.action)
                 FROM role_permissions rp
                 JOIN permissions p ON p.id = rp.permission_id
                 JOIN modules m ON m.id = p.module_id
                 WHERE rp.role_id = r.id), '[]'::jsonb),
       'BASELINE'
FROM roles r
ON CONFLICT (role_id, version_no) DO NOTHING;
