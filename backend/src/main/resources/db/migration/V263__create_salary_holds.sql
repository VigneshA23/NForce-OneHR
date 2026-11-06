-- US-C26 (ONEHR-579) — "Hold processing" (Keka's own term): an employee excluded entirely from a
-- DRAFT run's payslip generation for one cycle. See SalaryHold's own Javadoc for why "Hold payout"
-- (processed normally, payment withheld) is explicitly NOT built here yet.

CREATE TABLE salary_holds (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id uuid NOT NULL REFERENCES payroll_runs(id),
    employee_id uuid NOT NULL REFERENCES employees(user_id),
    reason text NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    released_by uuid,
    released_at timestamptz,
    CONSTRAINT chk_salary_holds_status CHECK (status IN ('ACTIVE', 'RELEASED'))
);

CREATE INDEX idx_salary_holds_run_employee ON salary_holds(payroll_run_id, employee_id);
