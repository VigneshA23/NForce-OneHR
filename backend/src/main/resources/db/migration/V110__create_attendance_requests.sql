-- NForce OneHR — Flyway Migration V93
-- Work From Home / Partial Day requests: a forward-looking self-declaration of work mode for
-- a given date, distinct from regularization (which corrects a past punch) and web clock-in
-- (which self-declares an actual check-in). Shared table for both types, discriminated by
-- request_type — see AttendanceRequest entity / AttendanceRequestService.

CREATE TABLE IF NOT EXISTS attendance_requests (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id      UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_approver_id  UUID         REFERENCES users(id),
    request_type          VARCHAR(20)  NOT NULL CHECK (request_type IN ('WFH', 'PARTIAL_DAY')),
    request_date          DATE         NOT NULL,
    -- Only meaningful for PARTIAL_DAY; NULL for WFH (enforced in AttendanceRequestService).
    partial_day_hours     NUMERIC(4,2),
    reason                TEXT         NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by           UUID         REFERENCES users(id),
    reviewed_at           TIMESTAMPTZ,
    review_comment        TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_attendance_requests_employee ON attendance_requests(employee_user_id);
CREATE INDEX idx_attendance_requests_status   ON attendance_requests(status);
