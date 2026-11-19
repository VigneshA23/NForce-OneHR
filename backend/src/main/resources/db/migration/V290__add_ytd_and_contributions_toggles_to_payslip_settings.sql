-- Two more opt-in Body Customisation toggles, both defaulting to the renderer's existing
-- behaviour (no YTD column, no Contributions section) so an existing payslip is unchanged until
-- HR turns either on.

ALTER TABLE payslip_settings
    ADD COLUMN include_ytd_column boolean NOT NULL DEFAULT FALSE,
    ADD COLUMN include_employer_contributions boolean NOT NULL DEFAULT FALSE;
