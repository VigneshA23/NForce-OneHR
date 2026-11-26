-- NForce OneHR - Flyway Migration V316
-- PN-02: an employee dispute of an attendance penalty. Routed to the employee's current
-- manager (PENDING_MANAGER), then HR (PENDING_HR), and resolved UPHELD (penalty stands) or
-- REVERSED (penalty reversed through AttendancePenaltyService, the same path the manager cancel
-- and regularization reversal use). At most one dispute per penalty - an upheld penalty cannot be
-- re-contested.

CREATE TABLE attendance_penalty_disputes (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    penalty_id             UUID         NOT NULL REFERENCES attendance_penalties(id) ON DELETE CASCADE,
    employee_user_id       UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason                 TEXT         NOT NULL,
    evidence               TEXT,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'PENDING_MANAGER'
                               CHECK (status IN ('PENDING_MANAGER', 'PENDING_HR', 'UPHELD', 'REVERSED')),
    manager_user_id        UUID         REFERENCES users(id),
    manager_recommendation VARCHAR(10)  CHECK (manager_recommendation IN ('UPHOLD', 'REVERSE')),
    manager_comment        TEXT,
    manager_acted_at       TIMESTAMPTZ,
    resolved_by            UUID         REFERENCES users(id),
    resolution_comment     TEXT,
    resolved_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_attendance_penalty_disputes_penalty UNIQUE (penalty_id)
);
CREATE INDEX idx_penalty_disputes_employee ON attendance_penalty_disputes(employee_user_id);
CREATE INDEX idx_penalty_disputes_status   ON attendance_penalty_disputes(status);
