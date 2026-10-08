-- US-B04/US-B06/US-B10: projects an employee can be allocated to, and the allocation rows
-- themselves (employee x project x date range x capacity%). No overlap is forbidden outright
-- (unlike penalization_policy_allocations) — an employee may hold several concurrent allocations
-- as long as their capacity_percent never sums past 100 for any overlapping day, enforced at the
-- application layer in ProjectAllocationService.

CREATE TABLE projects (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL UNIQUE,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE project_allocations (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id  UUID        NOT NULL REFERENCES employees(user_id),
    project_id        UUID        NOT NULL REFERENCES projects(id),
    capacity_percent  INT         NOT NULL CHECK (capacity_percent BETWEEN 1 AND 100),
    start_date        DATE        NOT NULL,
    end_date          DATE        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT project_allocations_range_check CHECK (end_date >= start_date)
);

CREATE INDEX idx_project_allocations_employee_dates ON project_allocations(employee_user_id, start_date, end_date);
CREATE INDEX idx_project_allocations_project ON project_allocations(project_id);
