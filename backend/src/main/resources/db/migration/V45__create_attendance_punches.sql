-- NForce OneHR — Flyway Migration V45
-- Individual punch log, one row per check-in/check-out session. attendance_records (V11,
-- V44) stays the daily aggregate (first check-in, latest check-out, total worked minutes)
-- that the rest of the app already reads; this table exists purely so an employee's punch
-- history for a day (e.g. a lunch break) can be shown in full, not just the day's bookends.

CREATE TABLE attendance_punches (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attendance_record_id UUID NOT NULL REFERENCES attendance_records(id) ON DELETE CASCADE,
    check_in_at           TIMESTAMPTZ NOT NULL,
    check_out_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_attendance_punches_record ON attendance_punches(attendance_record_id);
