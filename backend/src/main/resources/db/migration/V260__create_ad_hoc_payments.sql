-- US-C24 (ONEHR-579) — one-off ad-hoc payments (bonus, OT, referral, ...) attached to a specific
-- employee's payslip within one DRAFT payroll run. See AdHocPayment's own Javadoc for why there's
-- no separate "processed" flag: mutation is gated by the run's own status instead.

CREATE TABLE ad_hoc_payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id uuid NOT NULL REFERENCES payroll_runs(id),
    employee_id uuid NOT NULL REFERENCES employees(user_id),
    component_id uuid NOT NULL REFERENCES salary_component_catalog(id),
    component_name varchar(50) NOT NULL,
    component_type varchar(20) NOT NULL,
    amount numeric(14,2) NOT NULL,
    reason text NOT NULL,
    added_by uuid NOT NULL,
    added_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_ad_hoc_payments_run_employee ON ad_hoc_payments(payroll_run_id, employee_id);
