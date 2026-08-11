-- NForce OneHR — Flyway Migration V94
-- Employee-submitted overtime requests, routed to the employee's manager (or a chosen eligible
-- approver) for approval — see OvertimeRequestService. Tracked/visible only; approval does not
-- adjust attendance_records.worked_minutes, since there's no shift-end (ONEHR-108) to reconcile
-- overtime hours against yet.

CREATE TABLE overtime_requests (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_approver_id  UUID         REFERENCES users(id),
    work_date             DATE         NOT NULL,
    requested_start       TIMESTAMPTZ  NOT NULL,
    requested_end         TIMESTAMPTZ  NOT NULL,
    reason                TEXT         NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by           UUID         REFERENCES users(id),
    reviewed_at           TIMESTAMPTZ,
    review_comment        TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (requested_end > requested_start)
);
CREATE INDEX idx_overtime_requests_employee ON overtime_requests(employee_user_id);
CREATE INDEX idx_overtime_requests_status   ON overtime_requests(status);
