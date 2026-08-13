-- NForce OneHR — Flyway Migration V103
-- (originally authored as V98, renumbered — the shared dev DB had already advanced to schema
-- version 102 via other branches' migrations under the same version numbers by the time this
-- was applied, so V98-102 were unavailable; see V95's header for the same precedent)
-- Manager: Regularize & Cancel Penalties. Net-new — no AttendancePenalty concept existed before.
-- No seed data, no active policy, no trigger populates this table today (see
-- NoActivePolicyEngine / AttendancePenaltyEvaluationService): this migration only creates the
-- shape penalties will land in once a real policy exists.

CREATE TABLE IF NOT EXISTS attendance_penalties (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id     UUID         NOT NULL REFERENCES users(id),
    incident_date        DATE         NOT NULL,
    discrepancy_type     VARCHAR(30)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING_REVIEW',

    -- Policy snapshot at evaluation time — deliberately not a foreign key to a policy table
    -- (none exists yet) so a policy row can be deleted later without breaking historical rows.
    policy_id            UUID,
    policy_version       INTEGER,
    evaluated_at         TIMESTAMPTZ  NOT NULL,
    penalized_on         TIMESTAMPTZ  NOT NULL,

    cancelled_by         UUID REFERENCES users(id),
    cancelled_at         TIMESTAMPTZ,
    cancellation_reason  TEXT,

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_attendance_penalties_employee_date
    ON attendance_penalties (employee_user_id, incident_date);

CREATE INDEX IF NOT EXISTS idx_attendance_penalties_status
    ON attendance_penalties (status);
