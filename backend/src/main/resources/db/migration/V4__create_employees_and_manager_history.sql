-- NForce OneHR — Flyway Migration V4
-- Employee Master data + effective-dated manager history.
--
-- DIFF from provided spec:
--   department_id/designation_id/location_id changed INTEGER → UUID
--   to match V3's UUID primary keys on those tables.
--   full_name VARCHAR(200) added — users table has no name columns.

CREATE TABLE employees (
    user_id         UUID         PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    employee_code   VARCHAR(20)  NOT NULL UNIQUE,
    full_name       VARCHAR(200) NOT NULL,
    department_id   UUID         REFERENCES departments(id),
    designation_id  UUID         REFERENCES designations(id),
    location_id     UUID         REFERENCES locations(id),
    employment_type VARCHAR(30)  NOT NULL DEFAULT 'FULL_TIME',
    joining_date    DATE         NOT NULL,
    created_by      UUID         REFERENCES users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_employees_department  ON employees(department_id);
CREATE INDEX idx_employees_designation ON employees(designation_id);
CREATE INDEX idx_employees_location    ON employees(location_id);

CREATE TABLE employee_manager_history (
    id               BIGSERIAL   PRIMARY KEY,
    employee_user_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    manager_user_id  UUID        NOT NULL REFERENCES users(id),
    effective_from   TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to     TIMESTAMPTZ,
    changed_by       UUID        REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mgr_history_employee ON employee_manager_history(employee_user_id);
CREATE INDEX idx_mgr_history_manager  ON employee_manager_history(manager_user_id);
CREATE UNIQUE INDEX idx_mgr_history_one_current
    ON employee_manager_history(employee_user_id)
    WHERE effective_to IS NULL;
