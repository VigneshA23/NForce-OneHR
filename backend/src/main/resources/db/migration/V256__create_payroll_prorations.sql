-- US-C07 (ONEHR-613), Epic ONEHR-579, Phase 2 — new-joiner/exit pro-ration + exit leave
-- encashment per employee per pay-cycle window. See PayrollProration's own doc comment for the
-- formula source. All columns updated in place on recompute (reproducible from Employee.joiningDate/
-- lastWorkingDay) — no override story exists for this one, unlike payroll_lop_calculations.

CREATE TABLE payroll_prorations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id uuid NOT NULL REFERENCES employees(user_id),
    cycle_start date NOT NULL,
    cycle_end date NOT NULL,
    cycle_days int NOT NULL,
    employed_days int NOT NULL,
    proration_factor numeric(5,4) NOT NULL,
    proration_type varchar(20) NOT NULL,
    leave_encashment_days numeric(6,2),
    computed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_payroll_prorations_type CHECK (
        proration_type IN ('FULL_CYCLE', 'JOINER', 'EXIT', 'JOINER_AND_EXIT')
    )
);

CREATE UNIQUE INDEX uq_payroll_prorations_employee_cycle
    ON payroll_prorations(employee_id, cycle_start, cycle_end);
