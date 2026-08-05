-- NForce OneHR — Flyway Migration V52
-- Per-stage approval audit convenience columns on regularization_requests. The full history
-- of every decision already lives in regularization_approvals (V41) — these four columns just
-- make the two stages of a request (manager approval, then final HR/Super Admin approval)
-- directly queryable/displayable without a join, alongside the existing reviewed_by/reviewed_at
-- (which continue to reflect the *most recent* decision at either stage).
--
-- All nullable, no backfill: pre-existing APPROVED rows predate the two-stage model, so leaving
-- them NULL is correct — their history remains available via reviewed_by/reviewed_at and
-- regularization_approvals.
ALTER TABLE regularization_requests
    ADD COLUMN approved_by       UUID REFERENCES users(id),
    ADD COLUMN approved_at       TIMESTAMPTZ,
    ADD COLUMN final_approved_by UUID REFERENCES users(id),
    ADD COLUMN final_approved_at TIMESTAMPTZ;
