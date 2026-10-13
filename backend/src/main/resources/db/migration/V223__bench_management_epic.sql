-- ONEHR-577 Workforce Availability & Bench Management: everything the stories after US-A01 need.
-- One migration (not one per story) because the shared dev DB only ever moves forward.

-- V214 (projects / project_allocations) sits below this shared dev DB's current version, so
-- Flyway skipped it as "ignored" and the tables may not exist here. The bench stories need them
-- (allocation-linked bench end, last project, opportunity closing), so create them idempotently
-- with exactly V214's definitions; a DB that already has them is unaffected.
CREATE TABLE IF NOT EXISTS projects (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL UNIQUE,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS project_allocations (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id  UUID        NOT NULL REFERENCES employees(user_id),
    project_id        UUID        NOT NULL REFERENCES projects(id),
    capacity_percent  INT         NOT NULL CHECK (capacity_percent BETWEEN 1 AND 100),
    start_date        DATE        NOT NULL,
    end_date          DATE        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT project_allocations_range_check CHECK (end_date >= start_date)
);

CREATE INDEX IF NOT EXISTS idx_project_allocations_employee_dates ON project_allocations(employee_user_id, start_date, end_date);
CREATE INDEX IF NOT EXISTS idx_project_allocations_project ON project_allocations(project_id);

-- US-A04/US-A05/US-A06: aging bucket + notification dedupe + close-out details on bench_status.
ALTER TABLE bench_status
    ADD COLUMN aging_bucket  VARCHAR(40),
    ADD COLUMN notified_at   TIMESTAMPTZ,
    ADD COLUMN close_reason  VARCHAR(40)
        CHECK (close_reason IS NULL OR close_reason IN ('RETURNED_TO_ACTIVE', 'RESIGNED', 'OTHER')),
    ADD COLUMN close_note    VARCHAR(500),
    ADD COLUMN allocation_id UUID REFERENCES project_allocations(id);

-- US-A04: HR-editable aging bucket boundaries. max_days NULL = open-ended (the last bucket).
CREATE TABLE bench_aging_bucket_config (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    bucket_label VARCHAR(40) NOT NULL UNIQUE,
    min_days     INT         NOT NULL CHECK (min_days >= 0),
    max_days     INT,
    CONSTRAINT bench_aging_bucket_range CHECK (max_days IS NULL OR max_days >= min_days)
);
INSERT INTO bench_aging_bucket_config (bucket_label, min_days, max_days) VALUES
    ('0-15 days', 0, 15), ('16-30 days', 16, 30), ('31-60 days', 31, 60), ('60+ days', 61, NULL);

-- US-A05: single-row org settings (the threshold lives next to the bucket config, per the story).
CREATE TABLE bench_settings (
    id                    INT PRIMARY KEY CHECK (id = 1),
    notify_threshold_days INT NOT NULL CHECK (notify_threshold_days >= 1)
);
INSERT INTO bench_settings (id, notify_threshold_days) VALUES (1, 30);

-- US-A03: nightly snapshot so the 90-day trend never joins 90 days of bench_status rows on read.
CREATE TABLE bench_dashboard_snapshots (
    snapshot_date   DATE PRIMARY KEY,
    benched_count   INT  NOT NULL,
    total_headcount INT  NOT NULL
);

-- US-A07: managed skill taxonomy + per-employee 1-5 ratings (self and manager).
CREATE TABLE skill_taxonomy (
    id       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    skill    VARCHAR(100) NOT NULL UNIQUE,
    category VARCHAR(100) NOT NULL
);

CREATE TABLE employee_skill_ratings (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID        NOT NULL REFERENCES employees(user_id),
    skill_id         UUID        NOT NULL REFERENCES skill_taxonomy(id),
    self_rating      INT         NOT NULL CHECK (self_rating BETWEEN 1 AND 5),
    manager_rating   INT         CHECK (manager_rating BETWEEN 1 AND 5),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_employee_skill_rating UNIQUE (employee_user_id, skill_id)
);
CREATE INDEX idx_employee_skill_ratings_skill ON employee_skill_ratings(skill_id);

-- US-A10/A11/A12: internal opportunities and interest.
CREATE TABLE internal_opportunities (
    id                        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title                     VARCHAR(200) NOT NULL,
    duration_weeks            INT          NOT NULL CHECK (duration_weeks >= 1),
    status                    VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    posted_by_user_id         UUID         NOT NULL REFERENCES users(id),
    assigned_employee_user_id UUID         REFERENCES employees(user_id),
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at                 TIMESTAMPTZ
);

CREATE TABLE opportunity_required_skills (
    opportunity_id UUID NOT NULL REFERENCES internal_opportunities(id) ON DELETE CASCADE,
    skill_id       UUID NOT NULL REFERENCES skill_taxonomy(id),
    PRIMARY KEY (opportunity_id, skill_id)
);

CREATE TABLE opportunity_interests (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    opportunity_id   UUID        NOT NULL REFERENCES internal_opportunities(id) ON DELETE CASCADE,
    employee_user_id UUID        NOT NULL REFERENCES employees(user_id),
    expressed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_opportunity_interest UNIQUE (opportunity_id, employee_user_id)
);

-- US-A13: banded compensation only. Deliberately a standalone lookup — never a join against
-- individual salary data — so the bench cost view cannot expose personal pay.
CREATE TABLE compensation_bands (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    band_label VARCHAR(60)   NOT NULL UNIQUE,
    avg_ctc    NUMERIC(14,2) NOT NULL CHECK (avg_ctc >= 0)
);

CREATE TABLE employee_compensation_bands (
    employee_user_id UUID PRIMARY KEY REFERENCES employees(user_id),
    band_id          UUID NOT NULL REFERENCES compensation_bands(id)
);
