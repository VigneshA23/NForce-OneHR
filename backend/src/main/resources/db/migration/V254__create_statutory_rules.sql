-- US-C13 (Epic ONEHR-579): statutory deduction rules (PF/ESI/PT/TDS, ...), config/versioning
-- only — no seed data, no computation reads this table yet. effective_to IS NULL = current row
-- for a given (country, rule_type), same convention as employee_compensation/
-- employee_manager_history. slab_config is opaque jsonb: its shape is a jurisdiction/Phase-3
-- decision (see StatutoryRule's own doc comment), not something this migration decides.

CREATE TABLE statutory_rules (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    country varchar(10) NOT NULL,
    rule_type varchar(30) NOT NULL,
    slab_config jsonb NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- At most one open row per (country, rule_type) — same versioning invariant as
-- uq_employee_compensation_current (V253).
CREATE UNIQUE INDEX uq_statutory_rules_current ON statutory_rules(country, rule_type) WHERE effective_to IS NULL;

CREATE INDEX idx_statutory_rules_country_rule_type ON statutory_rules(country, rule_type);
