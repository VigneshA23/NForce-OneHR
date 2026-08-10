-- NForce OneHR — Flyway Migration V95
-- (originally authored as V92, renumbered — the shared dev DB had already advanced to schema
-- version 94 via other migrations by the time this was applied, so V92-94 were unavailable)
-- Manager: Bulk-Edit Team Shift, Weekly Off & Penalisation Policy Assignments (ONEHR-108).
-- Net-new administrative data model — no shift/weekly-off/penalisation concept existed before
-- this (Employee.work_mode is a separate, unrelated ONSITE/REMOTE/HYBRID attribute). Seeded
-- with the single default of each kind shown in the ONEHR-72 prototype; real policy variety
-- (multiple shift timings, region-specific weekly offs, tiered penalisation) is TBD with PO.

CREATE TABLE IF NOT EXISTS shifts (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    start_time  TIME         NOT NULL,
    end_time    TIME         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO shifts (name, start_time, end_time) VALUES
    ('Regular Shift', '09:00', '18:00')
ON CONFLICT (name) DO NOTHING;

CREATE TABLE IF NOT EXISTS weekly_off_policies (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    -- Comma-separated java.time.DayOfWeek names, e.g. 'SATURDAY,SUNDAY'.
    off_days    VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO weekly_off_policies (name, off_days) VALUES
    ('Standard Weekly Off Policy', 'SATURDAY,SUNDAY')
ON CONFLICT (name) DO NOTHING;

CREATE TABLE IF NOT EXISTS penalisation_policies (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO penalisation_policies (name, description) VALUES
    ('Default Tracking Policy', 'Standard late-arrival and attendance tracking, no automatic deductions.')
ON CONFLICT (name) DO NOTHING;

ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS shift_id UUID REFERENCES shifts(id),
    ADD COLUMN IF NOT EXISTS weekly_off_policy_id UUID REFERENCES weekly_off_policies(id),
    ADD COLUMN IF NOT EXISTS penalisation_policy_id UUID REFERENCES penalisation_policies(id);

-- Every existing employee gets the seeded defaults so no row is left unassigned.
UPDATE employees SET shift_id = (SELECT id FROM shifts WHERE name = 'Regular Shift')
    WHERE shift_id IS NULL;
UPDATE employees SET weekly_off_policy_id = (SELECT id FROM weekly_off_policies WHERE name = 'Standard Weekly Off Policy')
    WHERE weekly_off_policy_id IS NULL;
UPDATE employees SET penalisation_policy_id = (SELECT id FROM penalisation_policies WHERE name = 'Default Tracking Policy')
    WHERE penalisation_policy_id IS NULL;
