-- Non-destructive migration step (not the data migration itself — see V220/the Migration Review
-- screen for that): adds the new reference columns alongside the existing free-text ones, both
-- nullable, both left in place. employee_skills.skill_name/proficiency_level are NEVER dropped —
-- a row is only considered "migrated" once skill_id is set, and until then every existing reader
-- (self-service Skills tab, TeamGrowthService's manager/HR view) keeps working unchanged by
-- falling back to the free-text columns whenever the *_id column is still null.
ALTER TABLE employee_skills
    ADD COLUMN IF NOT EXISTS skill_id             UUID REFERENCES skill_master(id),
    ADD COLUMN IF NOT EXISTS proficiency_level_id UUID REFERENCES proficiency_level(id);
CREATE INDEX IF NOT EXISTS idx_employee_skills_skill ON employee_skills(skill_id);

ALTER TABLE employee_certificates
    ADD COLUMN IF NOT EXISTS category_id UUID REFERENCES certificate_category(id);
CREATE INDEX IF NOT EXISTS idx_employee_certificates_category ON employee_certificates(category_id);
CREATE INDEX IF NOT EXISTS idx_employee_certificates_expiry ON employee_certificates(expiry_date);
