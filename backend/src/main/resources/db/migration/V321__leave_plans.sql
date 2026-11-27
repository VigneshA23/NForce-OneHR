-- LV-01 (ONEHR-886): Leave Plans. V321: renumbered from V319 (dev took V319 comp_off, V320 early-going rules).
--
-- A leave plan bundles leave types with their own rules and is assigned to employees. Per-type
-- configuration (quota, crediting frequency, carry-forward, "applies to") moves from the leave type
-- onto the plan, so leave_types becomes a catalogue (name, code, paid/unpaid, per-occurrence basis).
--
--   leave_plans                  name, description, leave-year start month (January only for now),
--                                ACTIVE/INACTIVE, at most one org default.
--   leave_plan_versions          effective-dated snapshots of a plan's rules (policy -> version, as for
--                                penalization policies). Versions never overlap; only the last is open.
--   leave_plan_type_rules        one row per (version, leave type): the type is in the plan, with these rules.
--   leave_plan_type_eligibility  "applies to" rules of one plan type (was leave_type_eligibility_rules).
--   leave_plan_type_overrides    include/exclude employees of one plan type (was leave_type_employee_overrides).
--   leave_plan_allocations       explicit per-employee assignment, effective-dated, never overlapping
--                                (same shape as penalization_policy_allocations). Rule-based assignment
--                                uses the shared assignment_rules engine (domain LEAVE_PLAN, V318).
--
-- Resolution for an employee on a date: explicit allocation -> rule outcome -> org default.
--
-- Nothing changes on release day: one org-default plan "Default" reproduces the current configuration.
-- It gets one version per distinct leave_entitlement_policies.effective_year, holding every leave type
-- with the days/frequency in force that year, the type's carry-forward settings and its eligibility.
-- The old columns/tables stay untouched (read by nothing) for rollback; a later cleanup drops them.
--
-- Rollback: DROP the seven tables below; the app version before LV-01 reads the old tables unchanged.

CREATE TABLE leave_plans (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(100) NOT NULL,
    description             VARCHAR(500),
    leave_year_start_month  SMALLINT     NOT NULL DEFAULT 1,
    status                  VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    is_org_default          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by              UUID         REFERENCES users(id),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- The leave year is still January-December everywhere; other start months come with a follow-up.
    CONSTRAINT chk_leave_plan_year_start CHECK (leave_year_start_month = 1)
);
CREATE UNIQUE INDEX uq_leave_plans_name ON leave_plans (lower(name));
CREATE UNIQUE INDEX uq_leave_plans_org_default ON leave_plans (is_org_default) WHERE is_org_default;

CREATE TABLE leave_plan_versions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id         UUID        NOT NULL REFERENCES leave_plans(id) ON DELETE CASCADE,
    version         INTEGER     NOT NULL,
    effective_from  DATE        NOT NULL,
    effective_to    DATE,
    created_by      UUID        REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (plan_id, version),
    UNIQUE (plan_id, effective_from),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE UNIQUE INDEX uq_leave_plan_versions_open ON leave_plan_versions (plan_id) WHERE effective_to IS NULL;
ALTER TABLE leave_plan_versions
    ADD CONSTRAINT leave_plan_versions_no_overlap
    EXCLUDE USING gist (plan_id WITH =, daterange(effective_from, effective_to, '[]') WITH &&);

CREATE TABLE leave_plan_type_rules (
    id                           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id                   UUID         NOT NULL REFERENCES leave_plan_versions(id) ON DELETE CASCADE,
    leave_type_id                UUID         NOT NULL REFERENCES leave_types(id) ON DELETE CASCADE,
    -- Paid types: days per leave year (ANNUAL) or per occurrence (PER_OCCURRENCE). Unpaid: null (unlimited).
    days                         NUMERIC(6,2),
    credit_frequency             VARCHAR(10)  NOT NULL DEFAULT 'YEARLY' CHECK (credit_frequency IN ('YEARLY', 'MONTHLY')),
    carry_forward_enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    carry_forward_max_days       NUMERIC(6,2),
    carry_forward_expiry_months  INTEGER,
    carry_forward_priority       VARCHAR(30)  NOT NULL DEFAULT 'CARRY_FORWARD_FIRST'
                                 CHECK (carry_forward_priority IN ('CARRY_FORWARD_FIRST', 'CURRENT_FIRST')),
    UNIQUE (version_id, leave_type_id)
);
CREATE INDEX idx_leave_plan_type_rules_type ON leave_plan_type_rules (leave_type_id);

CREATE TABLE leave_plan_type_eligibility (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id    UUID         NOT NULL REFERENCES leave_plan_type_rules(id) ON DELETE CASCADE,
    rule_type  VARCHAR(20)  NOT NULL CHECK (rule_type IN ('GENDER', 'DEPARTMENT', 'LOCATION', 'EMPLOYMENT_TYPE')),
    value      VARCHAR(100) NOT NULL,
    UNIQUE (rule_id, rule_type, value)
);

CREATE TABLE leave_plan_type_overrides (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id           UUID        NOT NULL REFERENCES leave_plan_type_rules(id) ON DELETE CASCADE,
    employee_user_id  UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mode              VARCHAR(10) NOT NULL CHECK (mode IN ('INCLUDE', 'EXCLUDE')),
    UNIQUE (rule_id, employee_user_id)
);

CREATE TABLE leave_plan_allocations (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id  UUID        NOT NULL REFERENCES employees(user_id) ON DELETE CASCADE,
    plan_id           UUID        NOT NULL REFERENCES leave_plans(id),
    effective_from    DATE        NOT NULL,
    effective_to      DATE,
    created_by        UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by        UUID,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT leave_plan_allocations_range_check CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX idx_lpa_employee ON leave_plan_allocations (employee_user_id, effective_from);
CREATE INDEX idx_lpa_plan ON leave_plan_allocations (plan_id);
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE leave_plan_allocations
    ADD CONSTRAINT leave_plan_allocations_no_overlap
    EXCLUDE USING gist (employee_user_id WITH =, daterange(effective_from, effective_to, '[]') WITH &&);

-- Seed the Default plan ────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    v_plan_id UUID;
    yrs       INT[];
    i         INT;
    ver_id    UUID;
BEGIN
    INSERT INTO leave_plans (name, description, is_org_default)
    VALUES ('Default', 'Applies to every employee not assigned another plan.', TRUE)
    RETURNING id INTO v_plan_id;

    -- One version per year in which any entitlement changed; the first also covers all earlier dates.
    SELECT COALESCE(array_agg(y ORDER BY y), ARRAY[2000])
    INTO yrs
    FROM (SELECT DISTINCT effective_year AS y FROM leave_entitlement_policies) s;
    IF yrs[1] > 2000 THEN
        yrs := array_prepend(2000, yrs);
    END IF;

    FOR i IN 1 .. array_length(yrs, 1) LOOP
        INSERT INTO leave_plan_versions (plan_id, version, effective_from, effective_to)
        VALUES (v_plan_id, i, make_date(yrs[i], 1, 1),
                CASE WHEN i < array_length(yrs, 1) THEN make_date(yrs[i + 1], 1, 1) - 1 END)
        RETURNING id INTO ver_id;

        -- Every leave type (inactive ones too: their balances still exist), with the policy in force that year.
        INSERT INTO leave_plan_type_rules (version_id, leave_type_id, days, credit_frequency, carry_forward_enabled,
                                           carry_forward_max_days, carry_forward_expiry_months, carry_forward_priority)
        SELECT ver_id, lt.id,
               CASE WHEN lt.classification = 'PAID' THEN COALESCE(p.days, 0) END,
               COALESCE(p.credit_frequency, 'YEARLY'),
               lt.carry_forward_enabled, lt.carry_forward_max_days, lt.carry_forward_expiry_months,
               lt.carry_forward_priority
        FROM leave_types lt
        LEFT JOIN LATERAL (
            SELECT ep.days, ep.credit_frequency FROM leave_entitlement_policies ep
            WHERE ep.leave_type_id = lt.id AND ep.effective_year <= yrs[i]
            ORDER BY ep.effective_year DESC LIMIT 1
        ) p ON TRUE;
    END LOOP;

    INSERT INTO leave_plan_type_eligibility (rule_id, rule_type, value)
    SELECT r.id, e.rule_type, e.value
    FROM leave_plan_type_rules r
    JOIN leave_plan_versions v ON v.id = r.version_id AND v.plan_id = v_plan_id
    JOIN leave_type_eligibility_rules e ON e.leave_type_id = r.leave_type_id;

    INSERT INTO leave_plan_type_overrides (rule_id, employee_user_id, mode)
    SELECT r.id, o.employee_user_id, o.mode
    FROM leave_plan_type_rules r
    JOIN leave_plan_versions v ON v.id = r.version_id AND v.plan_id = v_plan_id
    JOIN leave_type_employee_overrides o ON o.leave_type_id = r.leave_type_id;
END $$;
