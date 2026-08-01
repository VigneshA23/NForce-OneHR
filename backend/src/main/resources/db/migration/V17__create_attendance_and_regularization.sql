-- NForce OneHR — Flyway Migration V17
-- Attendance Regularization feature.
--
-- Design notes:
-- 1. attendance_records already exists (see V11) as the real punch-clock table used by
--    the check-in/check-out feature that landed on dev in parallel with this branch.
--    Regularization approval upserts into that same table (by employee_user_id +
--    work_date) rather than owning a second, incompatible copy of it — see V18 for the
--    'source' column added there to flag regularized rows.
-- 2. regularization_requests allows multiple requests per employee per date OVER TIME
--    (e.g. rejected then resubmitted), but a partial unique index blocks more than one
--    concurrently PENDING request for the same date.
-- 3. Notification-on-approve/reject is deliberately NOT part of this schema — owned
--    by another workstream; see RegularizationService for the TODO hook points.

CREATE TABLE regularization_requests (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    attendance_date      DATE         NOT NULL,
    requested_check_in   TIMESTAMPTZ,
    requested_check_out  TIMESTAMPTZ,
    reason               TEXT         NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by          UUID         REFERENCES users(id),
    reviewed_at          TIMESTAMPTZ,
    review_comment       TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT regularization_requires_a_time
        CHECK (requested_check_in IS NOT NULL OR requested_check_out IS NOT NULL)
);
CREATE INDEX idx_regularization_employee ON regularization_requests(employee_user_id);
CREATE INDEX idx_regularization_status   ON regularization_requests(status);

-- Only one PENDING request per employee per date — resubmission after
-- reject/approve is fine, concurrent duplicate pending requests are not.
CREATE UNIQUE INDEX idx_regularization_one_pending_per_date
    ON regularization_requests(employee_user_id, attendance_date)
    WHERE status = 'PENDING';
