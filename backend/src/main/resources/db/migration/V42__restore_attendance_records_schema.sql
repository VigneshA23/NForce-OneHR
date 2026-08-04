-- NForce OneHR — Flyway Migration V42
-- The shared dev DB's attendance_records table was altered outside of any
-- committed migration on any branch: columns renamed (employee_user_id ->
-- user_id, work_date -> attendance_date, check_in_at -> check_in,
-- check_out_at -> check_out), worked_minutes and late_by_minutes dropped,
-- and the status CHECK swapped to PRESENT/ABSENT/HALF_DAY/ON_LEAVE instead
-- of PRESENT/LATE/HALF_DAY/ABSENT. None of this exists in git history on
-- any branch, so there is nothing to migrate forward from — it was applied
-- directly against this database and never committed.
--
-- The table is empty in the shared dev DB (verified before writing this),
-- so the safe fix is to drop and recreate it exactly as V11 + V18 (source
-- column) define it, which is what Attendance.java / AttendanceRepository
-- actually query against.

DROP TABLE IF EXISTS attendance_records;

CREATE TABLE attendance_records (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    work_date        DATE        NOT NULL,
    check_in_at      TIMESTAMPTZ NOT NULL,
    check_out_at     TIMESTAMPTZ,
    worked_minutes   INTEGER,
    status           VARCHAR(20) NOT NULL DEFAULT 'PRESENT'
                     CHECK (status IN ('PRESENT', 'LATE', 'HALF_DAY', 'ABSENT')),
    late_by_minutes  INTEGER     NOT NULL DEFAULT 0,
    source           VARCHAR(20) NOT NULL DEFAULT 'SYSTEM'
                     CHECK (source IN ('SYSTEM', 'REGULARIZATION')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT attendance_records_employee_date_unique UNIQUE (employee_user_id, work_date)
);

CREATE INDEX idx_attendance_records_work_date ON attendance_records(work_date);
CREATE INDEX idx_attendance_records_employee  ON attendance_records(employee_user_id);
