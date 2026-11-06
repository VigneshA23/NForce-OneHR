-- US-C08 (ONEHR-614), Epic ONEHR-579, Phase 3 — the payroll run: PayrollRun (one per pay-group
-- cycle), Payslip (one per employee per run), PayslipLineItem (one per component per payslip).
-- Status model incorporates US-C27 from the start: APPROVED/DISBURSED only changes via rollback
-- (service-layer guard), reverting status to DRAFT — never a direct UPDATE in place.

CREATE TABLE payroll_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    pay_group_id uuid NOT NULL REFERENCES pay_groups(id),
    cycle_start date NOT NULL,
    cycle_end date NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'DRAFT',
    employee_count int NOT NULL DEFAULT 0,
    total_gross numeric(14,2) NOT NULL DEFAULT 0,
    total_deductions numeric(14,2) NOT NULL DEFAULT 0,
    total_net numeric(14,2) NOT NULL DEFAULT 0,
    submitted_at timestamptz,
    approved_at timestamptz,
    disbursed_at timestamptz,
    last_rolled_back_at timestamptz,
    last_rollback_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_payroll_runs_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'DISBURSED'))
);

CREATE UNIQUE INDEX uq_payroll_runs_pay_group_cycle ON payroll_runs(pay_group_id, cycle_start, cycle_end);

CREATE TABLE payslips (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id uuid NOT NULL REFERENCES payroll_runs(id),
    employee_id uuid NOT NULL REFERENCES employees(user_id),
    ctc numeric(14,2) NOT NULL,
    cycle_days int NOT NULL,
    lop_days numeric(6,2) NOT NULL,
    employed_days int NOT NULL,
    proration_factor numeric(5,4) NOT NULL,
    gross_pay numeric(14,2) NOT NULL,
    total_deductions numeric(14,2) NOT NULL,
    net_pay numeric(14,2) NOT NULL,
    released boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_payslips_run_employee ON payslips(payroll_run_id, employee_id);

CREATE TABLE payslip_line_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payslip_id uuid NOT NULL REFERENCES payslips(id),
    component_id uuid NOT NULL REFERENCES salary_component_catalog(id),
    component_name varchar(50) NOT NULL,
    component_type varchar(20) NOT NULL,
    amount numeric(14,2) NOT NULL,
    prorated boolean NOT NULL DEFAULT false
);

CREATE INDEX idx_payslip_line_items_payslip ON payslip_line_items(payslip_id);
