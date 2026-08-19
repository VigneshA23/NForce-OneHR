-- NForce OneHR — Flyway Migration V96
-- Repeats V19's one-time leave balance seed for every employee onboarded since it ran.
-- V19 only ever ran once against whichever employees existed in the `employees` table at
-- that moment; UserManagementService/EmployeeService now create balances going forward
-- (see LeaveService.initializeDefaultBalances), but existing gaps still need this backfill.
-- (Originally authored as V92/V93 — renumbered to V96/V97: the shared dev DB already had
-- an uncommitted Helpdesk migration occupying 92-95, applied directly and never checked in.)

INSERT INTO leave_balances (employee_user_id, leave_type_id, year, total_days, used_days)
SELECT e.user_id, lt.id, EXTRACT(YEAR FROM CURRENT_DATE)::INT, 20, 0
FROM employees e
CROSS JOIN leave_types lt
ON CONFLICT (employee_user_id, leave_type_id, year) DO NOTHING;
