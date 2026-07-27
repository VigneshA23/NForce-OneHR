-- NForce OneHR — Flyway Migration V6
-- Add work_mode to employees: REMOTE / HYBRID / ONSITE (default ONSITE).

ALTER TABLE employees
    ADD COLUMN work_mode VARCHAR(20) NOT NULL DEFAULT 'ONSITE'
    CHECK (work_mode IN ('REMOTE', 'HYBRID', 'ONSITE'));
