-- ONEHR bug report: "Assets Assigned" card (AssetService#hrTileSummary, counts
-- asset_assignments WHERE effective_to IS NULL) overcounted relative to the inventory list's own
-- ASSIGNED-status assets. Root cause: AssetService#retireAsset set the asset's status to RETIRED
-- without ever closing its open assignment row (see the accompanying code fix) — anyone who
-- retired a currently-assigned asset left a permanently-open asset_assignments row behind.
--
-- Backfill: close every open assignment row whose asset is no longer ASSIGNED. effective_to is
-- set to now() rather than backdated to the retirement moment (that moment isn't recorded
-- anywhere — Asset has no "retired_at" column, only the audit log, which this migration has no
-- access to) — an approximate closure time is still correct for the one thing that matters here,
-- the "assigned right now" count, and no report in this app slices assignment history finely
-- enough for the exact closure instant to matter.
UPDATE asset_assignments aa
SET effective_to = now()
FROM assets a
WHERE aa.asset_id = a.id
  AND aa.effective_to IS NULL
  AND a.status <> 'ASSIGNED';
