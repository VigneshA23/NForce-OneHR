-- Roles & Permissions hardening, release R3 — EMPLOYEE becomes the default category.
--
-- Baseline employee self-service (profile, attendance, leave, requests…) has never been granted by
-- the EMPLOYEE role (it has zero role_permissions rows — see V225); it is the "authenticated floor".
-- From this release the application no longer assigns EMPLOYEE to anyone, and treats it only as the
-- default CATEGORY for people with no additional role (RoleCategorization). Legacy user_roles rows
-- for EMPLOYEE are still tolerated everywhere and are NOT removed here (a later release removes them
-- once this one has soaked), so this migration is data-description only.
--
-- Rollback: UPDATE roles SET description = 'Base role held by every user — self-service features.'
--           WHERE code = 'EMPLOYEE';
UPDATE roles
   SET description = 'Default category for people with no additional role. Baseline self-service access is available to every user and does not depend on this role.'
 WHERE code = 'EMPLOYEE';
