-- NForce OneHR — Flyway Migration V19
-- Leave Management: types, per-employee balances, and requests (ONEHR-37/38/39).
--
-- Policy defaults (real leave policy is TBD with PO — see ONEHR-37/39):
--   * 3 leave types seeded: Annual, Sick, Casual.
--   * Balance is decremented only on manager approval, never on submission.
--   * Day count is calendar days inclusive (end - start + 1), 0.5 for a half-day.
--   * Opening balance: 20 days per type per employee for the current year.

CREATE TABLE IF NOT EXISTS leave_types (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(20)  NOT NULL UNIQUE,
    name        VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO leave_types (code, name) VALUES
    ('ANNUAL', 'Annual Leave'),
    ('SICK',   'Sick Leave'),
    ('CASUAL', 'Casual Leave')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS leave_balances (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    leave_type_id    UUID        NOT NULL REFERENCES leave_types(id),
    year             INTEGER     NOT NULL,
    total_days       NUMERIC(5,1) NOT NULL DEFAULT 0,
    used_days        NUMERIC(5,1) NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (employee_user_id, leave_type_id, year)
);
CREATE INDEX IF NOT EXISTS idx_leave_balances_employee ON leave_balances(employee_user_id);

-- Seed an opening balance for every existing employee, every leave type, current year.
INSERT INTO leave_balances (employee_user_id, leave_type_id, year, total_days, used_days)
SELECT e.user_id, lt.id, EXTRACT(YEAR FROM CURRENT_DATE)::INT, 20, 0
FROM employees e
CROSS JOIN leave_types lt
ON CONFLICT (employee_user_id, leave_type_id, year) DO NOTHING;

CREATE TABLE IF NOT EXISTS leave_requests (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    leave_type_id    UUID         NOT NULL REFERENCES leave_types(id),
    start_date       DATE         NOT NULL,
    end_date         DATE         NOT NULL,
    is_half_day      BOOLEAN      NOT NULL DEFAULT FALSE,
    total_days       NUMERIC(5,1) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    employee_reason  TEXT         NOT NULL,
    decision_reason  TEXT,
    decided_by       UUID         REFERENCES users(id),
    decided_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_leave_requests_employee ON leave_requests(employee_user_id);
CREATE INDEX IF NOT EXISTS idx_leave_requests_status   ON leave_requests(status);
