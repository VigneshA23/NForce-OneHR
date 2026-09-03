-- Section 7 of the Penalization Policy hardening pass: the org-wide fallback policy has always
-- been an undocumented "oldest ACTIVE policy by createdAt" derivation
-- (PenalizationPolicyService#resolveActiveDefaultPolicyId) — there was no way for an admin to see
-- or choose which policy plays that role. This makes it an explicit, admin-settable flag instead.
--
-- Backfill: exactly the org's current oldest ACTIVE policy becomes the default, so existing single-
-- and multi-policy orgs see zero behavior change the moment this migration runs — only a
-- subsequent explicit "set default" action changes who governs unassigned employees from here on.
-- If no ACTIVE policy exists yet, no row is flagged (matches resolveActiveDefaultPolicyId's own
-- existing "no active policy to fall back to" failure mode).
ALTER TABLE penalisation_policies
    ADD COLUMN is_org_default BOOLEAN NOT NULL DEFAULT false;

UPDATE penalisation_policies
SET is_org_default = true
WHERE id = (
    SELECT id FROM penalisation_policies
    WHERE status = 'ACTIVE'
    ORDER BY created_at ASC
    LIMIT 1
);

-- At most one organization default, enforced at the database level rather than only by
-- PenalisationPolicyManagementService#setOrgDefault's read-then-write — the same fail-closed
-- pattern V154/V155/V156 already established for this feature area.
CREATE UNIQUE INDEX idx_penalisation_policies_one_org_default
    ON penalisation_policies (is_org_default)
    WHERE is_org_default = true;
