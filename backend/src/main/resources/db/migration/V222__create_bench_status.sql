-- US-A01: an employee's bench episodes (reason + date range). end_date NULL while active; at most
-- one active episode per employee, enforced by the partial unique index below.

CREATE TABLE bench_status (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id  UUID         NOT NULL REFERENCES employees(user_id),
    reason_code       VARCHAR(40)  NOT NULL
        CHECK (reason_code IN ('ROLE_ENDED', 'PROJECT_ENDED', 'NEW_JOINER_UNALLOCATED', 'AWAITING_ONBOARDING')),
    start_date        DATE         NOT NULL,
    end_date          DATE,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT bench_status_range_check CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT bench_status_active_check CHECK (active = (end_date IS NULL))
);

CREATE UNIQUE INDEX uq_bench_status_one_active ON bench_status(employee_user_id) WHERE active;
CREATE INDEX idx_bench_status_employee ON bench_status(employee_user_id);
