ALTER TABLE payslip_settings ADD COLUMN logo_size integer NOT NULL DEFAULT 70;
ALTER TABLE payslip_settings ADD COLUMN logo_shape varchar(10) NOT NULL DEFAULT 'SQUARE';
