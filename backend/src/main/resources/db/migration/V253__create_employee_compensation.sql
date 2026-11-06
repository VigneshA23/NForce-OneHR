-- US-C02 (Epic ONEHR-579): an employee's CTC and SalaryStructure assignment, effective-dated the
-- same way as employee_manager_history (effective_to IS NULL = current row). A revision is an
-- INSERT + one UPDATE to close the prior row's effective_to, never an UPDATE in place.

CREATE TABLE employee_compensation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id uuid NOT NULL REFERENCES employees(user_id),
    structure_id uuid NOT NULL REFERENCES salary_structures(id),
    ctc numeric(14,2) NOT NULL CHECK (ctc > 0),
    effective_from date NOT NULL,
    effective_to date,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- At most one open (effective_to IS NULL) row per employee — enforces the versioning invariant
-- at the DB level too, not just in EmployeeCompensationService.
CREATE UNIQUE INDEX uq_employee_compensation_current ON employee_compensation(employee_id) WHERE effective_to IS NULL;

CREATE INDEX idx_employee_compensation_employee ON employee_compensation(employee_id);
