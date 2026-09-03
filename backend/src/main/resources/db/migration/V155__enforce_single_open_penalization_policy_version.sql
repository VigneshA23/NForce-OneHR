-- Section 22/Gap-010 of the Penalization Policy audit: the invariant "at most one open
-- (effective_to IS NULL) version per policy" was previously enforced only by application logic
-- (PenalizationPolicyService's read-then-write save() flow), backed by a non-unique partial index
-- (V104) that documents the intent but does not enforce it. Two concurrent saves racing that
-- read-then-write path could leave two versions simultaneously open, making
-- findByPolicyIdAndEffectiveToIsNull's result ambiguous for policy resolution.
--
-- Safety: converting the index to UNIQUE simply fails this migration (and the whole app refuses
-- to start) if any existing policy already has more than one open version today — the same
-- fail-closed guarantee V154's exclusion constraint already relied on for allocations.
DROP INDEX IF EXISTS idx_penalization_policy_versions_current;

CREATE UNIQUE INDEX idx_penalization_policy_versions_current
    ON penalization_policy_versions (policy_id) WHERE effective_to IS NULL;
