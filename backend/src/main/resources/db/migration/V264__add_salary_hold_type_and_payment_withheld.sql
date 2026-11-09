-- US-C26 (ONEHR-579) — "Hold payout": the second hold mechanism Keka exposes, distinct from
-- "Hold processing" (V263). The employee IS processed normally (payslip built, statutory
-- deductions apply) but the actual payment is withheld at disbursement time. Needs a type
-- discriminator on salary_holds (every existing row means PROCESSING, the only kind that
-- existed before this migration) and a per-payslip flag disburse() can set, since disburse()
-- stays whole-run but one employee's payment within it can now be held back.

ALTER TABLE salary_holds ADD COLUMN hold_type varchar(20) NOT NULL DEFAULT 'PROCESSING';
ALTER TABLE salary_holds ADD CONSTRAINT chk_salary_holds_hold_type CHECK (hold_type IN ('PROCESSING', 'PAYOUT'));

ALTER TABLE payslips ADD COLUMN payment_withheld boolean NOT NULL DEFAULT false;
