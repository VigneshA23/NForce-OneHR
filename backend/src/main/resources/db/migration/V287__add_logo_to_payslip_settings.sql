-- Company logo printed on the payslip PDF, per pay group — same BYTEA-column pattern as
-- Employee.profile_photo/profile_cover; no separate file-storage system exists in this app.
-- Null for every existing row, so a payslip renders exactly as before until HR uploads one.

ALTER TABLE payslip_settings
    ADD COLUMN logo bytea,
    ADD COLUMN logo_content_type varchar(100);
