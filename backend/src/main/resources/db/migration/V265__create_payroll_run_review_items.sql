-- US-C22/US-C23 (ONEHR-579) — the two "run step" reviews Keka's walkthrough describes before LOP
-- is finalised: "Leave Applied" (unpaid-leave requests + penalization-policy deductions in the
-- cycle, bulk approve/reject) and "No Attendance" (employee-days with no attendance and no leave,
-- resolved per row as deduct-from-leave / mark LOP / no action). One table for both, discriminated
-- by step_type — they share almost everything (tied to one run, one employee, one date, one
-- decision, audited), and source_type/source_id only applies to the LEAVE_APPLIED step (a
-- NO_ATTENDANCE-step row has no backing record to point at, since nothing was filed for that day).

CREATE TABLE payroll_run_review_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id uuid NOT NULL REFERENCES payroll_runs(id),
    employee_id uuid NOT NULL REFERENCES employees(user_id),
    step_type varchar(20) NOT NULL,
    source_type varchar(20) NOT NULL,
    source_id uuid,
    item_date date NOT NULL,
    days numeric(6,2) NOT NULL,
    leave_type_name varchar(100),
    decision varchar(20) NOT NULL DEFAULT 'PENDING',
    selected_leave_type_id uuid,
    comment text,
    decided_by uuid,
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_payroll_review_step_type CHECK (step_type IN ('LEAVE_APPLIED', 'NO_ATTENDANCE')),
    CONSTRAINT chk_payroll_review_source_type CHECK (source_type IN ('LEAVE_UNPAID', 'PENALTY', 'NO_ATTENDANCE'))
);

CREATE INDEX idx_payroll_review_items_run ON payroll_run_review_items(payroll_run_id, step_type);
CREATE INDEX idx_payroll_review_items_run_employee ON payroll_run_review_items(payroll_run_id, employee_id);
