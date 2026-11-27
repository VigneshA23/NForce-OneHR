-- LV-02 (ONEHR-887): per-type rules inside a leave plan, enforced on submit and approve.
--
-- leave_plan_type_rules gains, per leave type in a plan version:
--   entitlement_mode               QUOTA (days a year / per occurrence), UNLIMITED (no balance: nothing is
--                                  checked or deducted) or EARNED_ONLY (nothing credited automatically; only
--                                  what is credited by hand or earned, e.g. Comp-Off).
--   min/max_days_per_request       a request's day count must be within these.
--   max_requests_per_month/_year   PENDING + APPROVED requests of the type starting in that month / year.
--   max_consecutive_days           this request together with adjacent leave of the same type
--                                  (only weekly offs / holidays in between).
--   notice_days                    days between the request (submission date) and its start date,
--   notice_applies_over_days       only for requests longer than this (null = always).
--   allow_half_day, allow_hourly, min_hours
--   attachment_required_over_days  an attachment is mandatory for requests longer than this
--                                  (0 = always), with attachment_allowed_types (CSV of extensions,
--                                  null = any) and attachment_max_mb.
-- leave_plan_type_clubbing: leave types that may not be taken adjacent to this one.
-- leave_request_attachments: the file attached to a leave request (one per request).
--
-- Nothing changes on release day: every new rule defaults to "no limit" and half-day stays allowed.
-- The Comp-Off type (V319) is marked EARNED_ONLY: its days come from comp-off credits, never a quota.
-- Rollback: drop the two tables and the new columns.

ALTER TABLE leave_plan_type_rules
    ADD COLUMN entitlement_mode               VARCHAR(12)  NOT NULL DEFAULT 'QUOTA'
        CHECK (entitlement_mode IN ('QUOTA', 'UNLIMITED', 'EARNED_ONLY')),
    ADD COLUMN min_days_per_request           NUMERIC(5,2) CHECK (min_days_per_request IS NULL OR min_days_per_request > 0),
    ADD COLUMN max_days_per_request           NUMERIC(5,2) CHECK (max_days_per_request IS NULL OR max_days_per_request > 0),
    ADD COLUMN max_requests_per_month         INTEGER      CHECK (max_requests_per_month IS NULL OR max_requests_per_month > 0),
    ADD COLUMN max_requests_per_year          INTEGER      CHECK (max_requests_per_year IS NULL OR max_requests_per_year > 0),
    ADD COLUMN max_consecutive_days           NUMERIC(5,2) CHECK (max_consecutive_days IS NULL OR max_consecutive_days > 0),
    ADD COLUMN notice_days                    INTEGER      CHECK (notice_days IS NULL OR notice_days >= 0),
    ADD COLUMN notice_applies_over_days       NUMERIC(5,2) CHECK (notice_applies_over_days IS NULL OR notice_applies_over_days >= 0),
    ADD COLUMN allow_half_day                 BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN allow_hourly                   BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN min_hours                      NUMERIC(4,2) CHECK (min_hours IS NULL OR min_hours > 0),
    ADD COLUMN attachment_required_over_days  NUMERIC(5,2) CHECK (attachment_required_over_days IS NULL OR attachment_required_over_days >= 0),
    ADD COLUMN attachment_allowed_types       VARCHAR(200),
    ADD COLUMN attachment_max_mb              INTEGER      CHECK (attachment_max_mb IS NULL OR attachment_max_mb BETWEEN 1 AND 10);

CREATE TABLE leave_plan_type_clubbing (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id                UUID NOT NULL REFERENCES leave_plan_type_rules(id) ON DELETE CASCADE,
    blocked_leave_type_id  UUID NOT NULL REFERENCES leave_types(id) ON DELETE CASCADE,
    UNIQUE (rule_id, blocked_leave_type_id)
);

CREATE TABLE leave_request_attachments (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    leave_request_id  UUID         NOT NULL UNIQUE REFERENCES leave_requests(id) ON DELETE CASCADE,
    file_name         VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    data              BYTEA        NOT NULL,
    uploaded_by       UUID         REFERENCES users(id),
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

UPDATE leave_plan_type_rules r SET entitlement_mode = 'EARNED_ONLY', days = 0
FROM leave_types t
WHERE t.id = r.leave_type_id AND t.code = 'COMP_OFF';
