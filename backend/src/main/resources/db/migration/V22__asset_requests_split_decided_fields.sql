-- Split asset_requests.decided_by/decided_at into separate manager and fulfillment fields.
-- Manager approval decision → manager_decided_by / manager_decided_at
-- HR fulfillment action     → fulfilled_by / fulfilled_at
-- Data preserved: existing decided_by/decided_at rows are migrated to manager_decided_by/manager_decided_at.

ALTER TABLE asset_requests RENAME COLUMN decided_by TO manager_decided_by;
ALTER TABLE asset_requests RENAME COLUMN decided_at TO manager_decided_at;
ALTER TABLE asset_requests ADD COLUMN fulfilled_by  UUID        REFERENCES users(id);
ALTER TABLE asset_requests ADD COLUMN fulfilled_at  TIMESTAMPTZ;
