-- NForce OneHR — Flyway Migration V7
-- Dev/demo seed: starter departments, designations, locations.
-- Safe to re-run (ON CONFLICT DO NOTHING). Not intended as real org structure.

INSERT INTO departments (id, name) VALUES
    (gen_random_uuid(), 'Engineering'),
    (gen_random_uuid(), 'Human Resources'),
    (gen_random_uuid(), 'Finance'),
    (gen_random_uuid(), 'Sales'),
    (gen_random_uuid(), 'Operations'),
    (gen_random_uuid(), 'Quality Engineering')
ON CONFLICT (name) DO NOTHING;

-- designations use column `title`, not `name`
INSERT INTO designations (id, title) VALUES
    (gen_random_uuid(), 'Software Engineer'),
    (gen_random_uuid(), 'Senior Software Engineer'),
    (gen_random_uuid(), 'Team Lead'),
    (gen_random_uuid(), 'Engineering Manager'),
    (gen_random_uuid(), 'HR Executive'),
    (gen_random_uuid(), 'HR Business Partner'),
    (gen_random_uuid(), 'Finance Analyst')
ON CONFLICT (title) DO NOTHING;

INSERT INTO locations (id, name) VALUES
    (gen_random_uuid(), 'Chennai HQ'),
    (gen_random_uuid(), 'Bangalore Office'),
    (gen_random_uuid(), 'Remote - India'),
    (gen_random_uuid(), 'Remote - Global')
ON CONFLICT (name) DO NOTHING;
