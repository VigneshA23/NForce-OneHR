-- NForce OneHR — Flyway Migration V249
-- US-C01 (ONEHR-607) — Payroll Epic (ONEHR-579). Replaces V247's simplified
-- EARNING/DEDUCTION + FIXED/PERCENT_OF_BASIC model with the org's actual current payroll system
-- (Keka)'s calculation model, confirmed against a walkthrough of how it runs payroll today:
--   - type gets CONTRIBUTION and REIMBURSEMENT alongside EARNING/DEDUCTION
--   - calc_type drops PERCENT_OF_BASIC in favor of the general PERCENT_OF_COMPONENT (Basic is
--     just another component to reference), adds PERCENT_OF_GROSS and BALANCING_REMAINDER
--   - a component can have a cap, an optional "use lower of calculated vs. cap" rule, and a
--     per-component Loss-of-Pay proration opt-out
--   - a structure is scoped to a financial year
--
-- Numbered V249: V247/V248 are both already claimed by other already-applied work (see V247's
-- own header for the V232-246 collision story) — always check flyway_schema_history on the
-- shared DB itself before claiming a version.
--
-- No rows existed against the narrower V247 constraints at the time this was written (the one
-- throwaway test structure was deleted first) — this is a clean ALTER, not a backfill.

-- ============================================================
-- SALARY STRUCTURES — scope a structure to a financial year
-- ============================================================
ALTER TABLE salary_structures ADD COLUMN financial_year VARCHAR(10);

-- ============================================================
-- SALARY COMPONENTS — broaden type/calc_type, add taxable/cap/use_lower_of_cap/prorate_on_lop,
-- make value nullable (BALANCING_REMAINDER has none), add the self-referencing FK calc_type
-- PERCENT_OF_COMPONENT resolves against.
-- ============================================================
ALTER TABLE salary_components DROP CONSTRAINT chk_salary_components_type;
ALTER TABLE salary_components ADD CONSTRAINT chk_salary_components_type
    CHECK (type IN ('EARNING', 'CONTRIBUTION', 'DEDUCTION', 'REIMBURSEMENT'));

ALTER TABLE salary_components DROP CONSTRAINT chk_salary_components_calc_type;
ALTER TABLE salary_components ADD CONSTRAINT chk_salary_components_calc_type
    CHECK (calc_type IN ('FIXED', 'PERCENT_OF_GROSS', 'PERCENT_OF_COMPONENT', 'BALANCING_REMAINDER'));

ALTER TABLE salary_components ALTER COLUMN value DROP NOT NULL;
ALTER TABLE salary_components ADD CONSTRAINT chk_salary_components_value_matches_calc_type
    CHECK (
        (calc_type = 'BALANCING_REMAINDER' AND value IS NULL)
        OR (calc_type <> 'BALANCING_REMAINDER' AND value IS NOT NULL)
    );

ALTER TABLE salary_components ADD COLUMN taxable BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE salary_components ADD COLUMN reference_component_id UUID REFERENCES salary_components(id);
ALTER TABLE salary_components ADD CONSTRAINT chk_salary_components_reference_matches_calc_type
    CHECK (
        (calc_type = 'PERCENT_OF_COMPONENT' AND reference_component_id IS NOT NULL)
        OR (calc_type <> 'PERCENT_OF_COMPONENT' AND reference_component_id IS NULL)
    );

ALTER TABLE salary_components ADD COLUMN cap NUMERIC(12,2);
ALTER TABLE salary_components ADD COLUMN use_lower_of_cap BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE salary_components ADD CONSTRAINT chk_salary_components_use_lower_of_cap_needs_cap
    CHECK (NOT use_lower_of_cap OR cap IS NOT NULL);

ALTER TABLE salary_components ADD COLUMN prorate_on_lop BOOLEAN NOT NULL DEFAULT TRUE;
