-- NForce OneHR — Flyway Migration V235
-- WFH / Partial Day (attendance_requests) and Overtime are now decided purely by permissions, so
-- a custom role's grants in these two modules take real effect — unlock them in the role editor.
--
-- What changed in code (AttendanceRequestService, OvertimeRequestService, ApprovalCenterService):
--   * HR_ADMIN/SUPER_ADMIN "see and review every request" override -> <module>:APPROVE:ALL
--   * MANAGER "may review a current direct report's request"        -> <module>:APPROVE:TEAM
--   * explicit "Assign To" approver must be MANAGER/HR_ADMIN         -> the candidate's roles other
--     than SUPER_ADMIN grant <module>:APPROVE:TEAM (Super Admin stays excluded, as before)
--   * Approval Center fetches each module's queue when the caller holds <module>:APPROVE:TEAM
-- No new permissions or grants are needed: V225 already seeds both modules' APPROVE to MANAGER at
-- TEAM and HR_ADMIN/SUPER_ADMIN at ALL, so ALL's holders are exactly {HR_ADMIN, SUPER_ADMIN} and
-- TEAM's (cascaded) exactly {MANAGER, HR_ADMIN, SUPER_ADMIN} — the sets the old role checks named.
-- EMPLOYEE holds neither. Every system role therefore behaves exactly as before.
--
-- Rollback: UPDATE modules SET custom_role_ready = FALSE WHERE code IN ('attendance_requests', 'overtime');

UPDATE modules SET custom_role_ready = TRUE WHERE code IN ('attendance_requests', 'overtime');
