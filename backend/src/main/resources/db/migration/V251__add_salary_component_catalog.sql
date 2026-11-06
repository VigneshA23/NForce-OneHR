-- NForce OneHR — Flyway Migration V251
-- US-C20 — Payroll Epic (ONEHR-579). Per US-C20's own dev notes ("the SalaryComponent table and
-- the seed list ship in ONEHR-607's migration"): introduces the shared Salary Component catalog
-- and reshapes the per-structure table (V247/V249/V250) into a join against it, instead of each
-- Salary Structure defining its own independent "Basic"/"HRA"/etc. rows.
--
-- No backfill needed — salary_structure_components (renamed from salary_components below) has 0
-- rows in the shared dev DB at the time of writing (confirmed before writing this migration).
--
-- Default tax/CTC/payslip flags below are deliberately conservative placeholders (taxable=TRUE,
-- no tax_section, included_in_ctc=TRUE, shown_on_payslip=TRUE) — exact tax-exemption rules per
-- component are a Finance/Payroll Admin call (US-C20's own admin UI edits these), not something
-- this migration decides. Only prorate_on_lop=FALSE for Conveyance is pinned, because that's
-- explicitly called out in US-C20's acceptance criteria.

-- ============================================================
-- SALARY COMPONENT CATALOG — the org-wide master list every Salary Structure selects from.
-- ============================================================
CREATE TABLE salary_component_catalog (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(50)  NOT NULL UNIQUE,
    type              VARCHAR(20)  NOT NULL,
    taxable           BOOLEAN      NOT NULL DEFAULT TRUE,
    tax_section       VARCHAR(50),
    included_in_ctc   BOOLEAN      NOT NULL DEFAULT TRUE,
    shown_on_payslip  BOOLEAN      NOT NULL DEFAULT TRUE,
    prorate_on_lop    BOOLEAN      NOT NULL DEFAULT TRUE,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_salary_component_catalog_type CHECK (
        type IN ('EARNING', 'AD_HOC_EARNING', 'DEDUCTION', 'AD_HOC_DEDUCTION', 'REIMBURSEMENT')
    )
);

-- Recurring (structural — selected into a Salary Structure).
INSERT INTO salary_component_catalog (name, type, prorate_on_lop) VALUES
    ('Basic', 'EARNING', TRUE),
    ('HRA', 'EARNING', TRUE),
    ('LTA (Travel Reimbursement)', 'REIMBURSEMENT', TRUE),
    ('Conveyance Allowance', 'EARNING', FALSE),
    ('Other/Special Allowance', 'EARNING', TRUE);

-- Ad-hoc earnings (applied to a specific payroll run, not part of a recurring structure).
INSERT INTO salary_component_catalog (name, type, active) VALUES
    ('Food Allowance', 'AD_HOC_EARNING', TRUE),
    ('Over Time (OT)', 'AD_HOC_EARNING', TRUE),
    ('Shift Allowance', 'AD_HOC_EARNING', TRUE),
    ('Incentives', 'AD_HOC_EARNING', TRUE),
    ('Previous Month Unpaid Days', 'AD_HOC_EARNING', TRUE),
    ('Referral Bonus', 'AD_HOC_EARNING', TRUE),
    ('Relocation Reimbursement', 'AD_HOC_EARNING', TRUE),
    ('Previous Month Adjustment', 'AD_HOC_EARNING', TRUE),
    ('Milestone Bonus', 'AD_HOC_EARNING', TRUE),
    ('Employee Expense', 'AD_HOC_EARNING', TRUE),
    ('Leave Encashment', 'AD_HOC_EARNING', FALSE);

-- Ad-hoc deductions.
INSERT INTO salary_component_catalog (name, type) VALUES
    ('Asset Damage Recovery (ADR)', 'AD_HOC_DEDUCTION'),
    ('Salary Adjustment for Previous Month', 'AD_HOC_DEDUCTION');

-- ============================================================
-- SALARY STRUCTURE COMPONENTS — renamed from salary_components (V247). Drops the columns that
-- moved to the catalog above (name/type/taxable/prorate_on_lop); keeps the structure-specific
-- calculation columns (calc_type/value/cap/use_lower_of_cap) and the self-reference used by
-- PERCENT_OF_COMPONENT, now pointed at a component_id in the catalog instead of carrying its own
-- name/type.
-- ============================================================
ALTER TABLE salary_components RENAME TO salary_structure_components;

ALTER TABLE salary_structure_components DROP CONSTRAINT chk_salary_components_type;
ALTER TABLE salary_structure_components DROP COLUMN type;
ALTER TABLE salary_structure_components DROP COLUMN name;
ALTER TABLE salary_structure_components DROP COLUMN taxable;
ALTER TABLE salary_structure_components DROP COLUMN prorate_on_lop;

ALTER TABLE salary_structure_components ADD COLUMN component_id UUID NOT NULL
    REFERENCES salary_component_catalog(id);

ALTER TABLE salary_structure_components
    RENAME COLUMN reference_component_id TO reference_structure_component_id;
