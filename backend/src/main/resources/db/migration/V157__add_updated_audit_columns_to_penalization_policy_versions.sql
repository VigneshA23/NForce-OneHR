-- Section 25/Gap-025 of the Penalization Policy audit: PenalizationPolicyVersion tracks
-- created_by/created_at but not updated_by/updated_at, unlike its sibling
-- PenalizationPolicyAllocation (V136) and HelpContent (V94). Most versions are truly immutable
-- after creation, but two genuine in-place mutations already exist in
-- PenalizationPolicyService#save: closing a superseded version's effective_to, and the
-- editingSameDateVersion path that rewrites a still-pending version's content in place — both
-- are row updates worth attributing on the row itself, not just in the audit log.
ALTER TABLE penalization_policy_versions
    ADD COLUMN updated_by UUID,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
