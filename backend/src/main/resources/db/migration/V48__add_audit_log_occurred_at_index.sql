-- The audit-history read API sorts/filters by occurred_at on every query; V1 only indexed
-- actor_id, target_id, and action.
CREATE INDEX idx_audit_occurred_at ON audit_log(occurred_at DESC);
