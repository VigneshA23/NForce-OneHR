-- Keka restyle (payroll module) — "Import Investment Declaration" (§3.4): declared amounts under
-- a tax section (e.g. 80C, 80D), per employee per financial year. Bookkeeping only — this
-- payroll engine doesn't compute TDS at all (see StatutoryDeductionService's own Javadoc: TDS
-- needs a full annual income-tax slab computation, explicitly out of scope), so unlike Keka's own
-- description nothing here is "considered when calculating TDS" yet. One row per
-- (employee, financial year, section) — importing the same combination again updates it in
-- place, matching Keka's own "Add/Update" wording for this import.

CREATE TABLE investment_declarations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id uuid NOT NULL,
    financial_year varchar(10) NOT NULL,
    section varchar(30) NOT NULL,
    declared_amount numeric(14,2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_investment_declarations_employee_fy_section UNIQUE (employee_id, financial_year, section)
);

CREATE INDEX idx_investment_declarations_employee ON investment_declarations(employee_id);
