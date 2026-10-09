-- Policy versioning with forced re-acknowledgment: HR needs a "Publish New Version" action,
-- separate from the existing metadata-only edit, that is explicitly linked to the policy it
-- supersedes (rather than the existing publish()'s implicit same-title matching) so acknowledgment
-- history can be reliably grouped/walked by version chain.
--
-- Each policy version is already its own row (policies.id) — publishing a version has always
-- meant inserting a new row and deactivating the old one (see PolicyService#publish), and
-- policy_acknowledgments already points at one specific policy row, so per-version acknowledgment
-- isolation requires no schema change. This migration only adds the explicit version-chain link
-- policyId-based publishing needs.
ALTER TABLE policies
    ADD COLUMN version_number      INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN previous_version_id BIGINT REFERENCES policies(id);

-- Existing policies are each version 1 of their own chain — the default above already gives them
-- that shape; no backfill needed.
