-- NForce OneHR — Flyway Migration V17
-- Attendance module (minimal) + Attendance Regularization feature.
--
-- Design notes:
-- 1. attendance_records is intentionally minimal (no punch-device integration yet) —
--    it exists so Regularization has a real record to upsert into on approval.
--    One row per (user_id, attendance_date).
-- 2. regularization_requests allows multiple requests per employee per date OVER TIME
--    (e.g. rejected then resubmitted), but a partial unique index blocks more than one
--    concurrently PENDING request for the same date.
-- 3. Notification-on-approve/reject is deliberately NOT part of this schema — owned
--    by another workstream; see RegularizationService for the TODO hook points.
-- 4. DROP TABLE IF EXISTS below is a no-op on a fresh database — kept defensively in
--    case this runs against a dev DB that already has an older prototype of this table.

DROP TABLE IF EXISTS attendance_records CASCADE;

CREATE TABLE attendance_records (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    attendance_date  DATE         NOT NULL,
    check_in         TIMESTAMPTZ,
    check_out        TIMESTAMPTZ,
    status           VARCHAR(20)  NOT NULL DEFAULT 'ABSENT'
                         CHECK (status IN ('PRESENT', 'ABSENT', 'HALF_DAY', 'ON_LEAVE')),
    source           VARCHAR(20)  NOT NULL DEFAULT 'SYSTEM'
                         CHECK (source IN ('SYSTEM', 'REGULARIZATION')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT attendance_records_user_date_unique UNIQUE (user_id, attendance_date)
);
CREATE INDEX idx_attendance_records_user ON attendance_records(user_id);
CREATE INDEX idx_attendance_records_date ON attendance_records(attendance_date);

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
