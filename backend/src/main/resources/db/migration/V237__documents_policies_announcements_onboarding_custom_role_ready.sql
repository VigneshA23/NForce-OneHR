-- NForce OneHR — Flyway Migration V237
-- Documents, Policies, Announcements and Onboarding: service-layer role-code checks replaced by
-- permission checks, so a custom role granted these modules' permissions behaves like HR/SA.
--
-- EQUIVALENCE: every service check used to be "has role HR_ADMIN or SUPER_ADMIN"; each is now the
-- same permission the endpoint's own @PreAuthorize already requires, and every documents/policies/
-- announcements/onboarding permission is seeded to exactly HR_ADMIN + SUPER_ADMIN (V225) — so the
-- system roles' behavior is unchanged.
--
-- The one narrower check — AnnouncementService's "only SUPER_ADMIN may see another author's drafts
-- or edit/publish/deactivate/delete another author's announcement" — had no permission with that
-- holder set, so it gets a new one, announcements:CONFIGURE ("manage every author's announcements"),
-- seeded to SUPER_ADMIN only (exactly today's holder; V233's trigger also grants it).
--
-- Not access control, deliberately untouched: policy/announcement audiences (role codes as content
-- targeting) and the "exclude HR/SA employees" filters on missing-document and acknowledgment lists.

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('announcements', 'CONFIGURE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('SUPER_ADMIN', 'announcements', 'CONFIGURE', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE modules SET custom_role_ready = TRUE
WHERE code IN ('documents', 'policies', 'announcements', 'onboarding');
