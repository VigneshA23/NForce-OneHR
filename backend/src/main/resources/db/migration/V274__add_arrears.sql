-- Keka's "Arrears" step — read-only in the UI, auto-computed from three triggers (backdated salary
-- revision, LOP reversal, salary hold release), never manually entered. amount is signed: positive
-- = owed to the employee, negative = a recovery. Independent of any specific PayrollRun until
-- applied (applied_to_run_id is null while PENDING) — see Arrears' own doc comment.
CREATE TABLE arrears (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id uuid NOT NULL REFERENCES employees(user_id),
    pay_group_id uuid NOT NULL REFERENCES pay_groups(id),
    amount numeric(14, 2) NOT NULL,
    reason text NOT NULL,
    source_type varchar(20) NOT NULL,
    source_cycle_start date NOT NULL,
    source_cycle_end date NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    applied_to_run_id uuid REFERENCES payroll_runs(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL
);

CREATE INDEX idx_arrears_employee_status ON arrears(employee_id, status);
