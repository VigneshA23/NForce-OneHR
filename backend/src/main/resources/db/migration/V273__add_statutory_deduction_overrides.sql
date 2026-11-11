-- Keka's "Statutory Override" step. Mirrors payroll_lop_calculations' own override columns and
-- keying exactly: one row per (employee_id, rule_type, cycle_start, cycle_end), independent of any
-- specific PayrollRun, so it survives regeneration the same way an LOP override does.
CREATE TABLE statutory_deduction_overrides (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id uuid NOT NULL REFERENCES employees(user_id),
    country varchar(10) NOT NULL,
    rule_type varchar(30) NOT NULL,
    cycle_start date NOT NULL,
    cycle_end date NOT NULL,
    override_amount numeric(14, 2) NOT NULL,
    override_reason text NOT NULL,
    overridden_by uuid NOT NULL,
    overridden_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- One override row per employee per rule type per cycle — createOrOverride upserts into this.
CREATE UNIQUE INDEX uq_statutory_override_employee_rule_cycle
    ON statutory_deduction_overrides(employee_id, rule_type, cycle_start, cycle_end);
