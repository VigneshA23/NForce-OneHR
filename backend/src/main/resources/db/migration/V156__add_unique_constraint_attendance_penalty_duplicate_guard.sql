-- Section 13/Gap-013 of the Penalization Policy audit: the invariant "at most one
-- AttendancePenalty per (employee, incident date, discrepancy type)" was previously enforced only
-- by an application-level read-then-write check (AttendancePenaltyEvaluationService#evaluate's
-- existsByEmployeeUserIdAndIncidentDateAndDiscrepancyType guard), which does not prevent two
-- concurrent evaluation runs from both passing the check before either commits.
--
-- All three columns are NOT NULL, and the domain genuinely allows multiple discrepancy types for
-- the same employee on the same date (e.g. LATE_ARRIVAL and WORK_HOURS_SHORTAGE both on one day),
-- so the unique index must span all three columns together, not just (employee, date).
--
-- The old 2-column index is dropped: a unique index on (employee_user_id, incident_date,
-- discrepancy_type) already serves as a leftmost-prefix index for the (employee_user_id,
-- incident_date) lookups findByEmployeeUserIdAndIncidentDate needs, so keeping both would be
-- redundant.
--
-- Safety: creating a UNIQUE index simply fails this migration (and the whole app refuses to
-- start) if a duplicate already exists today — the same fail-closed guarantee V154/V155 relied on.
DROP INDEX IF EXISTS idx_attendance_penalties_employee_date;

CREATE UNIQUE INDEX idx_attendance_penalties_employee_date_type
    ON attendance_penalties (employee_user_id, incident_date, discrepancy_type);
