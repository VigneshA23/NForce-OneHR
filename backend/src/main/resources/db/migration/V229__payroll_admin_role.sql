-- NForce OneHR — Flyway Migration V229
-- PAYROLL_ADMIN — the delegatable role a Super Admin can hand to an HR user so Payroll access
-- exists for SOMEONE even while it's fenced off from Super Admin themselves (see V228's header
-- and "Super Admin may assign fenced permissions to HR (governance) but never exercise them").
--
-- Deliberately a non-phase1 role (same tier as the pre-existing DELIVERY/FINANCE/LEADERSHIP
-- rows from V1) and deliberately NOT assignable through UserManagementService#updateUser's
-- primary-role-replace flow (that one swaps a user's whole role set for a single new primary
-- role) — it's an ADDITIVE secondary role granted/revoked independently, since a Payroll Admin
-- is still, say, an HR Admin first and foremost. Same payroll:VIEW/CONFIGURE:ALL stub grants
-- SUPER_ADMIN already holds (V228) — that module is still just a placeholder for ONEHR-579.
INSERT INTO roles (code, display_name, is_phase1) VALUES ('PAYROLL_ADMIN', 'Payroll Admin', FALSE);

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, 'ALL'
FROM roles r
JOIN modules m ON m.code = 'payroll'
JOIN permissions p ON p.module_id = m.id
WHERE r.code = 'PAYROLL_ADMIN';
