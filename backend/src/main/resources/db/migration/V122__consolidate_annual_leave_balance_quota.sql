-- NForce OneHR — Flyway Migration V122
-- (Originally authored as V121, renumbered — the shared dev DB already had a different V121
-- ["add token version to users"] applied from another branch by the time this ran.)
-- Consolidate Annual/Sick/Casual leave into ONE calculated/displayed "Annual Leave" balance.
-- Sick and Casual remain independently selectable leave types on request submission and their
-- LeaveType/LeaveBalance rows are left in place untouched — only the ANNUAL leave_balances
-- row's quota changes, since it becomes the single balance all three types draw from and
-- reserve against (see LeaveService#isAnnualBalanceLeaveType et al.).
--
-- Idempotent: only updates rows that don't already hold the target value, and only for the
-- ANNUAL leave type — Sick/Casual/any other leave type's total_days is left exactly as-is.
UPDATE leave_balances lb
SET total_days = 15,
    updated_at = now()
FROM leave_types lt
WHERE lb.leave_type_id = lt.id
  AND lt.code = 'ANNUAL'
  AND lb.total_days <> 15;
