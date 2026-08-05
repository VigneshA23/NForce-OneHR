-- NForce OneHR — Flyway Migration V46
-- Web Clock-In: any employee working remotely can self-declare a check-in with a
-- reason, subject to manager/HR approval — mirrors regularization_requests (V17).
-- Only on approval does it write to attendance_records (source WEB_REMOTE); check-out
-- afterward needs no approval, tracked directly on this row. One request per employee
-- per work date (a rejected request can be resubmitted; approved/pending cannot).

CREATE TABLE web_clock_in_requests (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    assigned_approver_id UUID         REFERENCES users(id),
    work_date            DATE         NOT NULL,
    requested_check_in   TIMESTAMPTZ  NOT NULL,
    reason               TEXT         NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    checked_out_at       TIMESTAMPTZ,
    reviewed_by          UUID         REFERENCES users(id),
    reviewed_at          TIMESTAMPTZ,
    review_comment       TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_web_clock_in_employee ON web_clock_in_requests(employee_user_id);
CREATE INDEX idx_web_clock_in_status   ON web_clock_in_requests(status);

-- At most one PENDING request per employee per day; resubmission after reject is fine.
CREATE UNIQUE INDEX idx_web_clock_in_one_pending_per_date
    ON web_clock_in_requests(employee_user_id, work_date)
    WHERE status = 'PENDING';

ALTER TABLE attendance_records DROP CONSTRAINT attendance_records_source_check;
ALTER TABLE attendance_records ADD CONSTRAINT attendance_records_source_check
    CHECK (source IN ('SYSTEM', 'REGULARIZATION', 'WEB_REMOTE'));
