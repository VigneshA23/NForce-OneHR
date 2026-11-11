-- US-B01 / US-B02 / US-B03: turn `projects` (V214/V223: just name + active flag) into maintained
-- reference data — unique code, start/end dates, owning department, owner (the Manager who
-- allocates on it), optional client tag, and an ACTIVE/ON_HOLD/CLOSED status that replaces the
-- boolean `active`. There is deliberately no delete path: CLOSED is a status, so
-- project_allocations.project_id stays a valid FK forever.
--
-- US-B02: `clients` is a plain name lookup (internal cost center or external client). No billing
-- columns, rate cards or invoices — out of scope for this phase by decision.

CREATE TABLE IF NOT EXISTS clients (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       CITEXT       NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE projects ADD COLUMN IF NOT EXISTS code              VARCHAR(50);
ALTER TABLE projects ADD COLUMN IF NOT EXISTS start_date        DATE;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS end_date          DATE;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS owner_employee_id UUID REFERENCES employees(user_id);
ALTER TABLE projects ADD COLUMN IF NOT EXISTS department_id     UUID REFERENCES departments(id);
ALTER TABLE projects ADD COLUMN IF NOT EXISTS client_id         UUID REFERENCES clients(id);
ALTER TABLE projects ADD COLUMN IF NOT EXISTS status            VARCHAR(20);

-- Backfill rows created before this migration: a generated code, the creation day as start date,
-- and a status carried over from the old boolean.
WITH numbered AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY created_at, id) AS n FROM projects WHERE code IS NULL
)
UPDATE projects p SET code = 'PRJ-' || LPAD(numbered.n::text, 4, '0')
FROM numbered WHERE p.id = numbered.id;

UPDATE projects SET start_date = created_at::date WHERE start_date IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'projects' AND column_name = 'active') THEN
        UPDATE projects SET status = CASE WHEN active THEN 'ACTIVE' ELSE 'CLOSED' END WHERE status IS NULL;
        ALTER TABLE projects DROP COLUMN active;
    END IF;
END $$;
UPDATE projects SET status = 'ACTIVE' WHERE status IS NULL;

ALTER TABLE projects ALTER COLUMN code       SET NOT NULL;
ALTER TABLE projects ALTER COLUMN start_date SET NOT NULL;
ALTER TABLE projects ALTER COLUMN status     SET NOT NULL;
ALTER TABLE projects ALTER COLUMN status     SET DEFAULT 'ACTIVE';

CREATE UNIQUE INDEX IF NOT EXISTS uq_projects_code_ci ON projects (LOWER(code));

ALTER TABLE projects DROP CONSTRAINT IF EXISTS projects_status_check;
ALTER TABLE projects ADD CONSTRAINT projects_status_check CHECK (status IN ('ACTIVE', 'ON_HOLD', 'CLOSED'));
ALTER TABLE projects DROP CONSTRAINT IF EXISTS projects_date_range_check;
ALTER TABLE projects ADD CONSTRAINT projects_date_range_check CHECK (end_date IS NULL OR end_date >= start_date);

CREATE INDEX IF NOT EXISTS idx_projects_owner ON projects (owner_employee_id);
