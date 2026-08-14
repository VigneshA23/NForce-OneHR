-- NForce OneHR — Flyway Migration V107
-- Keka reference: a Partial Day request is really one of three sub-modes (Late Arrive /
-- Intervening Time-off / Leaving Early), each with its own duration semantics — see
-- AttendanceRequestService. Also adds an optional "Notify" field (a specific colleague to alert
-- about the request, distinct from assigned_approver_id, which drives actual approval) to both
-- self-service request tables.

ALTER TABLE attendance_requests
    ADD COLUMN partial_day_mode VARCHAR(30)
        CHECK (partial_day_mode IN ('LATE_ARRIVE', 'INTERVENING_TIMEOFF', 'LEAVING_EARLY')),
    ADD COLUMN notify_user_id UUID REFERENCES users(id);

ALTER TABLE overtime_requests
    ADD COLUMN notify_user_id UUID REFERENCES users(id);
