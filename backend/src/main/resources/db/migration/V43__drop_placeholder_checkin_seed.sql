-- NForce OneHR — Flyway Migration V34
-- FR-004 (Attendance Management) has shipped: ExceptionService now detects
-- LATE_ARRIVAL and MISSING_PUNCH from real attendance_records data. Drop the
-- placeholder_checkin_seed table (V16) — it is no longer read anywhere.

DROP INDEX IF EXISTS idx_placeholder_checkin_date;
DROP INDEX IF EXISTS idx_placeholder_checkin_employee;
DROP TABLE IF EXISTS placeholder_checkin_seed;
