-- Help & Guidance self-service content: FAQ / Quick Help / Guide / Document, replacing the
-- hardcoded placeholder arrays in HelpDeskPage.tsx with HR-managed, published content.
-- Single table with a `type` discriminator (mirrors this schema's existing plain-VARCHAR
-- "enum" convention — helpdesk_tickets.status, document_types — rather than four near-identical
-- entities/tables for what is fundamentally the same shape).
--
-- Attachment columns mirror helpdesk_replies exactly (byte-in-Postgres, no new file-storage
-- mechanism). `audience` is reserved for future role/department targeting and is not filtered
-- on yet. `view_count` + `is_featured` + `display_order` drive FAQ ranking without hardcoding
-- a "top 5" — callers pass their own limit.

CREATE TABLE help_content (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    type             VARCHAR(20)  NOT NULL,   -- FAQ | QUICK_HELP | GUIDE | DOCUMENT
    title            VARCHAR(200) NOT NULL,
    description      VARCHAR(500),
    body             TEXT,
    category         VARCHAR(80),
    attachment_name  VARCHAR(255),
    attachment_type  VARCHAR(100),
    attachment_size  BIGINT,
    attachment_data  BYTEA,
    published        BOOLEAN      NOT NULL DEFAULT false,
    published_at     TIMESTAMPTZ,
    active           BOOLEAN      NOT NULL DEFAULT true,    -- archive = set false; keeps history instead of deleting
    is_featured      BOOLEAN      NOT NULL DEFAULT false,
    display_order    INTEGER      NOT NULL DEFAULT 0,
    view_count       BIGINT       NOT NULL DEFAULT 0,
    audience         VARCHAR(40)  NOT NULL DEFAULT 'ALL',   -- reserved for future role/department targeting
    created_by       UUID         NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       UUID REFERENCES users(id),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_help_content_type CHECK (type IN ('FAQ', 'QUICK_HELP', 'GUIDE', 'DOCUMENT'))
);

CREATE INDEX idx_help_content_type_published ON help_content(type, published, active);
