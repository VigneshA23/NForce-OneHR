-- Weekly Off Policy Versioning: makes a Policy's off-days definition effective-dated instead of a
-- single mutable value on the `weekly_off_policies` row itself — the same model V159
-- (shift_versions) already applied to a Shift's own timing. See WeeklyOffPolicyVersion's own
-- Javadoc for the full design — summary: every date-aware consumer resolves "the latest version
-- whose effective_from <= date" (WeeklyOffPolicyVersionResolver), so editing a policy's off days
-- can never silently reinterpret an already-recorded historical calculation for a date before the
-- new version takes effect.
--
-- `weekly_off_policies.off_days` is UNCHANGED by this migration and remains in the schema — it
-- becomes a best-effort, non-authoritative display cache of the CURRENT version going forward
-- (see application-code changes alongside this migration; kept in sync on every create/update),
-- mirroring employees.shift_id's identical relationship to employee_shift_assignments since V172.
CREATE TABLE IF NOT EXISTS weekly_off_policy_versions (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    weekly_off_policy_id   UUID         NOT NULL REFERENCES weekly_off_policies(id) ON DELETE CASCADE,
    off_days               TEXT         NOT NULL,
    effective_from         DATE         NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- At most one version of a given Policy may take effect on any single date — mirrors
    -- uq_shift_versions_shift_effective_from, and is what makes "replace the same-day version"
    -- (OrgService#updateWeeklyOffPolicy) a safe delete-then-insert.
    CONSTRAINT uq_weekly_off_policy_versions_policy_effective_from UNIQUE (weekly_off_policy_id, effective_from)
);

-- Existing policy rows become "Version 1" of themselves: effective_from is set to the policy's
-- own created_at date, the earliest date any real calculation could possibly reference it — not
-- invented, not a guess at when the CURRENT off_days value first became correct (this table has
-- no way to know whether/when an in-place edit happened before this migration existed). Same
-- "we cannot reconstruct history we never captured" limitation V159's own backfill (anchored to
-- shifts.created_at, for the exact same reason) already accepts — strictly no worse than today's
-- behavior, and now at least auditably labeled as a version rather than indistinguishable from
-- "current".
INSERT INTO weekly_off_policy_versions (weekly_off_policy_id, off_days, effective_from, created_at)
SELECT id, off_days, created_at::date, created_at
FROM weekly_off_policies;
