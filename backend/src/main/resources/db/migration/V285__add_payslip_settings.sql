-- Keka restyle (payroll module) — "Configure Payslip Settings" (§3.9), one row per pay group.
-- A row absent for a pay group means "never configured" — PayslipPdfService falls back to its
-- own long-standing hardcoded title/footer text exactly as before this existed, and renders
-- unencrypted, so an existing payslip's output never changes until someone opts in here.

CREATE TABLE payslip_settings (
    pay_group_id uuid PRIMARY KEY REFERENCES pay_groups(id),
    password_protected boolean NOT NULL DEFAULT FALSE,
    document_header_text varchar(50),
    footer_text text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
