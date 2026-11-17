-- Company address, printed under the company name on the payslip header — Keka-style payslips
-- show it; today this app only stores a legal entity name, no structured/free-text address.
-- Null for every existing pay group, so a payslip renders exactly as before until HR fills it in.

ALTER TABLE pay_groups
    ADD COLUMN address text;
