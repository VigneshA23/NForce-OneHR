-- Talent Search's "save this search and re-run it later" (e.g. "Java + AWS certified"). query_json
-- is the whole search form's state (skill AND/OR groups, proficiency/department/location filters,
-- etc.) as one opaque JSON blob — same JSONB-pass-through approach as AuditLog.beforeState/
-- afterState, so the search UI's own filter shape can evolve without another migration.
CREATE TABLE IF NOT EXISTS saved_talent_search (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id  UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name           VARCHAR(100) NOT NULL,
    query_json     JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_saved_talent_search_owner ON saved_talent_search(owner_user_id);
