-- NForce OneHR — Flyway Migration V47
-- Attendance Regularization: prevent more than one APPROVED regularization request per
-- employee per date, mirroring the existing PENDING-only partial unique index from V17.
-- The application already blocks this in RegularizationService (assertNoDuplicateRequest);
-- this is the DB-level backstop in case that check is ever bypassed.
--
-- Numbered V47 (not V42) because another branch's V42__restore_attendance_records_schema
-- (plus V43-V46) had already landed on the shared dev database first — see
-- flyway_schema_history before assuming a version number is free (same lesson V40's
-- header already recorded for V34).

CREATE UNIQUE INDEX idx_regularization_one_approved_per_date
    ON regularization_requests(employee_user_id, attendance_date)
    WHERE status = 'APPROVED';
