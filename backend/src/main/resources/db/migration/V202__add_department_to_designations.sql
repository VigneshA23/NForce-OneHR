-- Designations were org-wide with no Department relation at all, so Add User's Designation
-- dropdown could never filter by the selected Department (every designation showed for every
-- department, e.g. "QA Engineer III" selectable under Finance).
-- Nullable: existing rows stay unmapped (any department) until edited; OrgService now requires a
-- department on every newly created designation.
ALTER TABLE designations ADD COLUMN IF NOT EXISTS department_id UUID REFERENCES departments(id);

-- Backfill the dev/demo seed data (V7) so the feature is usable without manually re-editing every
-- designation.
UPDATE designations d SET department_id = dep.id
FROM departments dep
WHERE d.department_id IS NULL AND dep.name = 'Engineering'
  AND d.title IN ('Software Engineer', 'Senior Software Engineer', 'Team Lead', 'Engineering Manager');

UPDATE designations d SET department_id = dep.id
FROM departments dep
WHERE d.department_id IS NULL AND dep.name = 'Human Resources'
  AND d.title IN ('HR Executive', 'HR Business Partner');

UPDATE designations d SET department_id = dep.id
FROM departments dep
WHERE d.department_id IS NULL AND dep.name = 'Finance'
  AND d.title = 'Finance Analyst';
