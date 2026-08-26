-- NForce OneHR — Flyway Migration V102
-- (Originally authored as V98 — renumbered to V102: the shared dev DB had already advanced to
-- schema version 101 via other branches' uncommitted migrations by the time this was applied,
-- same collision documented in the V95/V96/V97 headers.)
-- FAQ & Guide approval workflow: HR/Super Admin authored content must now be approved by the
-- author's resolved manager (walking the reporting hierarchy, falling back to Super Admin)
-- before it can be published, and true multi-attachment support (add/remove/replace/reorder)
-- replaces the single BYTEA attachment column, since every attachment change must participate
-- in approval — including "removed and re-added the same file".
--
-- `published`/`active` (two booleans) are fully superseded by a single `status` column with
-- six values (DRAFT, PENDING_APPROVAL, APPROVED, PUBLISHED, UNPUBLISHED, ARCHIVED) — the richer
-- lifecycle no longer maps cleanly onto two booleans, so rather than layer a third concept on
-- top, `status` becomes the only source of truth and the booleans are dropped. `published_at`
-- is kept — still meaningful as "last published on".

-- digest() (used below to checksum attachment bytes) is a pgcrypto function, not a core builtin —
-- ensure the extension is present regardless of whether an earlier migration already relied on it.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE help_content ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'DRAFT';

-- One-time backfill from the old two-boolean model into the new single status column.
UPDATE help_content SET status = CASE
    WHEN NOT active THEN 'ARCHIVED'
    WHEN published THEN 'PUBLISHED'
    ELSE 'DRAFT'
END;

ALTER TABLE help_content ADD CONSTRAINT chk_help_content_status
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'UNPUBLISHED', 'ARCHIVED'));

-- Set only when this row is a draft revision of a still-PUBLISHED row (editing published
-- content must never touch the employee-visible row directly — see HelpContentService
-- .prepareForEdit). Publishing this row archives the row it supersedes.
ALTER TABLE help_content ADD COLUMN supersedes_id UUID NULL REFERENCES help_content(id) ON DELETE SET NULL;

-- Mirrors the latest rejection so the HR author sees why on the Draft, same convention as
-- RegularizationRequest.review_comment always mirroring the latest decision inline.
ALTER TABLE help_content ADD COLUMN rejection_reason TEXT NULL;

ALTER TABLE help_content DROP COLUMN published;
ALTER TABLE help_content DROP COLUMN active;

-- ── Multi-attachment (replaces the single attachment_name/type/size/data columns) ─────────
-- Same byte-in-Postgres convention already used by attachment_data here and by
-- HelpdeskReply/EmployeeDocument — no new file-storage mechanism.
CREATE TABLE help_content_attachment (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    content_id    UUID         NOT NULL REFERENCES help_content(id) ON DELETE CASCADE,
    display_order INT          NOT NULL DEFAULT 0,
    file_name     VARCHAR(255) NOT NULL,
    file_type     VARCHAR(100),
    file_size     BIGINT,
    file_data     BYTEA        NOT NULL,
    checksum      VARCHAR(64)  NOT NULL, -- sha-256 hex of file_data, used to detect "same file re-added"
    created_by    UUID         REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_help_content_attachment_content ON help_content_attachment(content_id);

INSERT INTO help_content_attachment (content_id, display_order, file_name, file_type, file_size, file_data, checksum, created_by, created_at)
SELECT id, 0, attachment_name, attachment_type, attachment_size, attachment_data, encode(digest(attachment_data, 'sha256'), 'hex'), created_by, created_at
FROM help_content
WHERE attachment_data IS NOT NULL;

ALTER TABLE help_content DROP COLUMN attachment_name;
ALTER TABLE help_content DROP COLUMN attachment_type;
ALTER TABLE help_content DROP COLUMN attachment_size;
ALTER TABLE help_content DROP COLUMN attachment_data;

-- ── Approval attempts — the permanent, immutable audit trail ───────────────────────────────
-- One row per Submit for Approval click. Never updated after being decided/withdrawn except to
-- record that outcome; RegularizationApproval is the precedent for "one immutable row per
-- decision", extended here with a full text snapshot since the approver must be able to compare
-- exactly what was submitted this time against the previous attempt.
CREATE TABLE help_content_approval (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    content_id             UUID         NOT NULL REFERENCES help_content(id) ON DELETE CASCADE,
    attempt_number         INT          NOT NULL,
    submitted_by           UUID         NOT NULL REFERENCES users(id),
    submitted_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approver_id            UUID         NOT NULL REFERENCES users(id),
    status                 VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decided_at             TIMESTAMPTZ  NULL,
    rejection_reason       TEXT         NULL,
    withdrawal_reason       TEXT         NULL,
    snapshot_title         VARCHAR(200) NOT NULL,
    snapshot_description   VARCHAR(500),
    snapshot_body          TEXT,
    snapshot_category      VARCHAR(80),
    snapshot_featured      BOOLEAN      NOT NULL DEFAULT FALSE,
    snapshot_display_order INT          NOT NULL DEFAULT 0,
    CONSTRAINT chk_help_content_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT uq_help_content_approval_attempt UNIQUE (content_id, attempt_number)
);
CREATE INDEX idx_help_content_approval_content ON help_content_approval(content_id);
CREATE INDEX idx_help_content_approval_approver ON help_content_approval(approver_id, status);

-- Only one PENDING attempt may exist per content row at a time — enforced here rather than
-- only in application code, same convention as employee_manager_history's
-- idx_mgr_history_one_current partial-unique index for "only one open row at a time".
CREATE UNIQUE INDEX idx_help_content_approval_one_pending
    ON help_content_approval(content_id) WHERE status = 'PENDING';

-- Attachment snapshot per attempt, so an approver can open the *previous* submitted file
-- itself (not just its name) when reviewing "View Changes".
CREATE TABLE help_content_approval_attachment (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_id   UUID         NOT NULL REFERENCES help_content_approval(id) ON DELETE CASCADE,
    display_order INT          NOT NULL DEFAULT 0,
    file_name     VARCHAR(255) NOT NULL,
    file_type     VARCHAR(100),
    file_size     BIGINT,
    file_data     BYTEA        NOT NULL,
    checksum      VARCHAR(64)  NOT NULL
);
CREATE INDEX idx_help_content_approval_attachment_approval ON help_content_approval_attachment(approval_id);
