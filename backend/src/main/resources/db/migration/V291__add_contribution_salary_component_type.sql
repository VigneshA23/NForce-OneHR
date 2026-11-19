-- New catalog type for employer-side amounts (e.g. "Employer PF") that print only in the payslip's
-- optional Contributions section — never counted as a deduction from net pay. Nothing is seeded
-- here; same self-serve convention as the existing PF/ESI/Professional Tax reserved names (an
-- admin creates the catalog entry when they want it to show).

ALTER TABLE salary_component_catalog DROP CONSTRAINT chk_salary_component_catalog_type;
ALTER TABLE salary_component_catalog ADD CONSTRAINT chk_salary_component_catalog_type CHECK (
    type IN ('EARNING', 'AD_HOC_EARNING', 'DEDUCTION', 'AD_HOC_DEDUCTION', 'REIMBURSEMENT', 'CONTRIBUTION')
);
