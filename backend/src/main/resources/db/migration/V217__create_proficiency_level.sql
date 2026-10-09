-- Replaces the hardcoded Beginner|Intermediate|Advanced|Expert @Pattern regex on
-- EmployeeSkillRequest with an HR/Super-Admin-configurable, orderable master list (Settings >
-- Proficiency Levels). Seeded with exactly the 4 existing values in their existing order so no
-- current employee_skills row or in-flight request becomes invalid the moment this ships —
-- employee_skills.proficiency_level (the free-string column) is untouched; V219 adds a nullable
-- proficiency_level_id alongside it, same non-destructive approach as skill_id.
CREATE TABLE IF NOT EXISTS proficiency_level (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        CITEXT      NOT NULL,
    sort_order  INTEGER     NOT NULL,
    description VARCHAR(255),
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT proficiency_level_name_unique UNIQUE (name)
);

INSERT INTO proficiency_level (name, sort_order, description)
VALUES
    ('Beginner', 1, 'Aware of the fundamentals; needs guidance applying them'),
    ('Intermediate', 2, 'Applies it independently on familiar problems'),
    ('Advanced', 3, 'Applies it to complex/unfamiliar problems; mentors others'),
    ('Expert', 4, 'Recognized go-to resource; shapes how the org uses it')
ON CONFLICT (name) DO NOTHING;
