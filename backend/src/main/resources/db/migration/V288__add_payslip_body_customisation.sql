-- Keka restyle (payroll module) — "Configure Payslip Settings" Body/Header Customisation
-- (§3.9), extending V285/V287. Every new column defaults to the behaviour PayslipPdfService
-- already had, so an existing pay group's payslip renders unchanged until HR opts in.

ALTER TABLE payslip_settings
    ADD COLUMN exclude_na_fields boolean NOT NULL DEFAULT FALSE,
    ADD COLUMN earnings_heading varchar(50),
    ADD COLUMN deductions_heading varchar(50),
    ADD COLUMN include_leave_summary boolean NOT NULL DEFAULT FALSE,
    ADD COLUMN include_arrear_breakup boolean NOT NULL DEFAULT FALSE,
    ADD COLUMN logo_alignment varchar(10) NOT NULL DEFAULT 'RIGHT',
    ADD COLUMN payslip_fields text;
