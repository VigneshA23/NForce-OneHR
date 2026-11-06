-- US-C04 (ONEHR-610) / US-C05 (ONEHR-611), Epic ONEHR-579, Phase 2 — Loss-of-Pay days per
-- employee per pay-cycle window (see PayrollLopCalculation's own doc comment for the formula
-- source and its open points). computed_* columns are updated in place on recompute (reproducible
-- from source data); override_* columns are the human decision and are never touched by a
-- recompute.

CREATE TABLE payroll_lop_calculations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id uuid NOT NULL REFERENCES employees(user_id),
    cycle_start date NOT NULL,
    cycle_end date NOT NULL,
    unpaid_leave_days numeric(6,2) NOT NULL,
    penalty_lop_days numeric(6,2) NOT NULL,
    auto_lop_no_attendance_days numeric(6,2) NOT NULL,
    computed_lop_days numeric(6,2) NOT NULL,
    needs_manual_review_dates jsonb NOT NULL,
    computed_at timestamptz NOT NULL,
    override_lop_days numeric(6,2),
    override_reason text,
    overridden_by uuid,
    overridden_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- One calculation row per employee per cycle — recompute upserts into this, never a duplicate row.
CREATE UNIQUE INDEX uq_payroll_lop_calculations_employee_cycle
    ON payroll_lop_calculations(employee_id, cycle_start, cycle_end);
