-- US-C21 — the rest of the "statutory and finance identifiers" set, merged into the same
-- employees table/access rule as the bank/PAN/UAN/ESI fields from US-C15.
ALTER TABLE employees ADD COLUMN account_holder_name varchar(150);
ALTER TABLE employees ADD COLUMN pf_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE employees ADD COLUMN pf_number varchar(30);
ALTER TABLE employees ADD COLUMN pf_registration_date date;
ALTER TABLE employees ADD COLUMN name_as_per_pf varchar(150);
ALTER TABLE employees ADD COLUMN esi_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE employees ADD COLUMN professional_tax_number varchar(30);
