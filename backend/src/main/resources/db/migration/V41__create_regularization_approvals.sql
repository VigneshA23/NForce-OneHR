-- NForce OneHR — Flyway Migration V41
-- Permanent audit trail for regularization decisions. regularization_requests (V17) keeps
-- only the *latest* decision inline (reviewed_by/reviewed_at/review_comment) — this table
-- appends one immutable row per approve/reject action so history is never overwritten,
-- even across a reject-then-resubmit-then-approve cycle on the same employee/date.

CREATE TABLE regularization_approvals (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id   UUID        NOT NULL REFERENCES regularization_requests(id) ON DELETE CASCADE,
    action_by    UUID        NOT NULL REFERENCES users(id),
    action_type  VARCHAR(20) NOT NULL CHECK (action_type IN ('APPROVED', 'REJECTED')),
    comments     TEXT,
    action_date  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_regularization_approvals_request ON regularization_approvals(request_id);
