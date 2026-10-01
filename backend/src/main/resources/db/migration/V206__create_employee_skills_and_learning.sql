-- Employee Skills & Expertise and Learning/Growth entries — both self-service, per-employee
-- lists, same shape as employee_education (see V196): simple owned rows, no approval workflow.
-- proficiency_level is a plain VARCHAR(20) rather than a DB enum/check constraint, matching this
-- codebase's existing convention for small fixed-choice fields owned at the application layer
-- (e.g. asset_requests.status, called out in V196).
CREATE TABLE IF NOT EXISTS employee_skills (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    skill_name         VARCHAR(100) NOT NULL,
    proficiency_level  VARCHAR(20)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_employee_skills_employee ON employee_skills(employee_user_id);

-- "What I learned" entries (courses, certifications, projects, skills picked up) — free-text
-- title/description + a single date, deliberately unstructured (no category field) per the
-- initial scope: employee self-service only, manager/lead visibility is an explicit later phase.
CREATE TABLE IF NOT EXISTS employee_learning_entries (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title              VARCHAR(255) NOT NULL,
    description        TEXT,
    entry_date         DATE         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_employee_learning_entries_employee ON employee_learning_entries(employee_user_id);
