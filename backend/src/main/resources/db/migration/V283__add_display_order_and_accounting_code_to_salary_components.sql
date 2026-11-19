-- Keka restyle (payroll module) — two small catalog-level fields backing the "Order of
-- components" and "Accounting code" setup screens:
--   display_order   — explicit ordering, the single source of truth everywhere a component list
--                      is shown (payslip, pay register, reports, salary breakup).
--                      SalaryComponentService#list switches its own default ordering to use it.
--   accounting_code — an optional ledger/GL code per component, admin-entered, never computed.

ALTER TABLE salary_component_catalog ADD COLUMN display_order integer NOT NULL DEFAULT 0;
ALTER TABLE salary_component_catalog ADD COLUMN accounting_code varchar(30);

-- Seed existing rows with a stable initial order (alphabetical by name, their old default sort)
-- rather than leaving every row at 0, where admins would have no natural starting arrangement.
WITH ordered AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY name ASC) AS rn FROM salary_component_catalog
)
UPDATE salary_component_catalog c SET display_order = ordered.rn
FROM ordered WHERE c.id = ordered.id;
