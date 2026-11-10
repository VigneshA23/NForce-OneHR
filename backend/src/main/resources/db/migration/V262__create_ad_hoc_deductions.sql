-- US-C25 (ONEHR-579) — ad-hoc deduction recovery plans (asset damage, overpayment, ...), recovered
-- in installments across however many payroll cycles it takes. See AdHocDeduction/
-- AdHocDeductionInstallment's own Javadoc for the regeneration/rollback idempotency model.

CREATE TABLE ad_hoc_deductions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id uuid NOT NULL REFERENCES employees(user_id),
    component_id uuid NOT NULL REFERENCES salary_component_catalog(id),
    component_name varchar(50) NOT NULL,
    component_type varchar(20) NOT NULL,
    total_amount numeric(14,2) NOT NULL,
    installment_amount numeric(14,2) NOT NULL,
    remaining_balance numeric(14,2) NOT NULL,
    reason text NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_ad_hoc_deductions_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX idx_ad_hoc_deductions_employee_status ON ad_hoc_deductions(employee_id, status);

CREATE TABLE ad_hoc_deduction_installments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ad_hoc_deduction_id uuid NOT NULL REFERENCES ad_hoc_deductions(id),
    payroll_run_id uuid NOT NULL REFERENCES payroll_runs(id),
    amount numeric(14,2) NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT now()
);

-- The idempotency guard regeneration relies on: at most one installment per (plan, run).
CREATE UNIQUE INDEX uq_ad_hoc_deduction_installments_deduction_run
    ON ad_hoc_deduction_installments(ad_hoc_deduction_id, payroll_run_id);
