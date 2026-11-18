-- Sensitive data protection, Phase 2 — an employee's own edit of bank / government-ID fields waits
-- for HR approval (employee_sensitive:APPROVE:ALL, V296) instead of taking effect immediately.
--
-- The request holds only the fields that actually changed: old_values (raw, as they were) and
-- new_values (raw, as submitted; a JSON null means "clear this field"). Nothing is written to
-- employees until an approver accepts it — see ProfileChangeRequestService.
-- One open request per employee: a new submission replaces the pending one's values.
-- Rollback: DROP TABLE profile_change_requests;
-- Idempotent (IF NOT EXISTS): the shared dev database's connection can drop after a migration's
-- statements commit but before Flyway records it, and a retry must then be harmless.
CREATE TABLE IF NOT EXISTS profile_change_requests (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    old_values        JSONB        NOT NULL,
    new_values        JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                          CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by       UUID         REFERENCES users(id),
    reviewed_at       TIMESTAMPTZ,
    review_comment    TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_profile_change_requests_employee ON profile_change_requests(employee_user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_profile_change_requests_one_pending
    ON profile_change_requests(employee_user_id) WHERE status = 'PENDING';
