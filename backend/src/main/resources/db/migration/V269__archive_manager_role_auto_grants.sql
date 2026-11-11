-- Roles & Permissions hardening, release R2 — retire automatic MANAGER grants.
--
-- From this release the MANAGER role is never granted, revoked or restored automatically:
-- assigning a reporting manager only records the reporting line, and manager access is assigned
-- explicitly by an administrator (Roles & Permissions / User Management). The application no longer
-- reads or writes manager_role_auto_grants.
--
-- DECISION (approved): existing automatic grants are CONVERTED TO EXPLICIT — nothing is revoked.
-- Every user keeps the MANAGER role they hold today, so no manager loses approval access. This
-- migration therefore changes NO user_roles / role_permissions row. It only snapshots the former
-- marker rows into a review table so an administrator can later decide, person by person, whether an
-- ex-"automatic" Manager should keep the role (e.g. via the Roles & Permissions page).
--
-- manager_role_auto_grants itself is intentionally left in place (unused) so this is fully
-- reversible; a later release drops it once the archive has been reviewed.
--
-- Review query (read-only), e.g. "ex-automatic Managers who no longer have any direct report":
--   SELECT a.user_id, u.email, a.direct_reports_at_archive, a.last_auto_grant_audit_at
--   FROM manager_role_auto_grants_archive a JOIN users u ON u.id = a.user_id
--   WHERE a.direct_reports_at_archive = 0;
--
-- Rollback: DROP TABLE manager_role_auto_grants_archive;  (the original table is untouched).
CREATE TABLE manager_role_auto_grants_archive AS
SELECT g.user_id,
       g.granted_at,
       (SELECT COUNT(*)
          FROM employee_manager_history h
          JOIN users e ON e.id = h.employee_user_id AND e.deleted_at IS NULL
         WHERE h.manager_user_id = g.user_id AND h.effective_to IS NULL) AS direct_reports_at_archive,
       (SELECT MAX(a.occurred_at)
          FROM audit_log a
         WHERE a.action = 'ROLE_AUTO_GRANTED' AND a.target_id = g.user_id)   AS last_auto_grant_audit_at,
       now()                                                                  AS archived_at
  FROM manager_role_auto_grants g;

ALTER TABLE manager_role_auto_grants_archive ADD PRIMARY KEY (user_id);
