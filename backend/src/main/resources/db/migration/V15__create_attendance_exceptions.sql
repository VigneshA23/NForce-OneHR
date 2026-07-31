-- NForce OneHR — Flyway Migration V15
-- Real, permanent attendance/leave exception ledger for the HR/Manager
-- Exception Dashboard. Not tied to FR-004 (Attendance Management)'s build
-- status: today it is populated from placeholder_checkin_seed (see V16);
-- when FR-004 ships, only the detection query in ExceptionService changes
-- to read real check-in/shift data — this table stays as-is.
--
-- exception_type is additive: only 'LATE_ARRIVAL' is populated today.
-- 'MISSING_PUNCH' is intentionally out of scope for this branch and will
-- be added as a future value once FR-004 provides real check-out data.

CREATE TABLE attendance_exceptions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    exception_date    DATE NOT NULL,
    exception_type    VARCHAR(30) NOT NULL,
    expected_time     TIME,
    actual_time       TIME,
    minutes_late      INTEGER,
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    source            VARCHAR(30) NOT NULL DEFAULT 'PLACEHOLDER_SEED',
    detected_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_attendance_exception_emp_date_type UNIQUE (employee_user_id, exception_date, exception_type)
);
CREATE INDEX idx_attendance_exceptions_employee ON attendance_exceptions(employee_user_id);
CREATE INDEX idx_attendance_exceptions_date     ON attendance_exceptions(exception_date);
