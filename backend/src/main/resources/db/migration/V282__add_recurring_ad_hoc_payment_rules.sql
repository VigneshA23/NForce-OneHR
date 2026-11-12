-- US-C24 follow-up (ONEHR-579) — a standing "apply this ad-hoc component to every employee every
-- cycle" rule, so a genuinely recurring allowance (e.g. Food Allowance) doesn't need re-entering
-- by hand on each new payroll run. PayrollRunService#generateDraft applies every ACTIVE rule
-- scoped to the run's pay group (pay_group_id NULL = every pay group) to every included employee,
-- same skip-if-already-present idempotency AdHocPaymentService#bulkAdd already uses.

CREATE TABLE recurring_ad_hoc_payment_rules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    pay_group_id uuid REFERENCES pay_groups(id),
    component_id uuid NOT NULL REFERENCES salary_component_catalog(id),
    component_name varchar(50) NOT NULL,
    component_type varchar(20) NOT NULL,
    amount numeric(14,2) NOT NULL,
    reason text NOT NULL,
    active boolean NOT NULL DEFAULT TRUE,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_recurring_ad_hoc_payment_rules_active ON recurring_ad_hoc_payment_rules(active);
