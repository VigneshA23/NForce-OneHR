-- Create Policy has no way to attach the actual policy document (image/PDF/Word) — only the
-- plain-text `description` field. Attachment is optional so existing text-only policies are
-- unaffected; mirrors HelpdeskReply's inline attachment columns (attachment_name/type/size/data).
-- IF NOT EXISTS: this is a shared dev database multiple branches migrate against directly (see
-- the flyway config comment in application.yml) — a prior run against it may already have added
-- these same columns.
ALTER TABLE policies
    ADD COLUMN IF NOT EXISTS attachment_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS attachment_type VARCHAR(100),
    ADD COLUMN IF NOT EXISTS attachment_size BIGINT,
    ADD COLUMN IF NOT EXISTS attachment_data BYTEA;
