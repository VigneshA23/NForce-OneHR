-- NForce OneHR — Flyway Migration V11
-- Employee attendance: one check-in/check-out pair per employee per calendar day.
--
-- Policy decided with PO: strict one pair per day. The UNIQUE (employee_user_id, work_date)
-- constraint is the real enforcement — the service-level duplicate check only exists to
-- return a friendly message. Under concurrent double-clicks the constraint is what holds.
--
-- work_date is computed server-side from the business timezone (app.attendance.zone,
-- default Asia/Kolkata), NOT from the JVM default zone — Railway runs UTC, which would
-- otherwise roll the work date over at 05:30 IST.
--
-- status ABSENT is allowed by the CHECK for future batch marking / reporting; this slice
-- never writes it (a day with no punch simply has no row).

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
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT attendance_records_employee_date_unique UNIQUE (employee_user_id, work_date)
);

CREATE INDEX idx_attendance_records_work_date ON attendance_records(work_date);
CREATE INDEX idx_attendance_records_employee  ON attendance_records(employee_user_id);
