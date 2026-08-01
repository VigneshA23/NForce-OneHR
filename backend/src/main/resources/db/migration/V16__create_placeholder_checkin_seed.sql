-- NForce OneHR — Flyway Migration V16
-- TEMPORARY / PLACEHOLDER — NOT PART OF REAL ATTENDANCE INFRASTRUCTURE.
--
-- FR-004 (Attendance Management: check-in/check-out, shift config, missing
-- punch, regularization) has not been built yet. This table exists ONLY to
-- give the Late Arrival detector (ExceptionService) something to read until
-- FR-004 ships real check-in events and shift configuration.
--
-- WHEN FR-004 LANDS: drop this table (new migration), delete the
-- PlaceholderCheckinSeed entity/repository/controller/DTOs, and repoint
-- ExceptionService's detection query at the real attendance tables.
-- Do not build anything else on top of this table.

CREATE TABLE placeholder_checkin_seed (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    work_date              DATE NOT NULL,
    shift_start_time       TIME NOT NULL DEFAULT '09:30:00',
    checkin_time           TIME NOT NULL,
    late_threshold_minutes INTEGER NOT NULL DEFAULT 15,
    created_by             UUID REFERENCES users(id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_placeholder_checkin_emp_date UNIQUE (employee_user_id, work_date)
);
CREATE INDEX idx_placeholder_checkin_employee ON placeholder_checkin_seed(employee_user_id);
CREATE INDEX idx_placeholder_checkin_date     ON placeholder_checkin_seed(work_date);
