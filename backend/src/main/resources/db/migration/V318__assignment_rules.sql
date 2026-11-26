-- NForce OneHR - Flyway Migration V318
-- PN-04: rule-based policy auto-assignment - ONE generic rule engine shared by every policy
-- domain (PENALIZATION now; LEAVE_PLAN / WFH_POLICY plug in through the same tables).
--
--  assignment_rules             HR-defined rules: criteria (department / location / shift /
--                               employment type / grade, all that are set must match) + priority
--                               (lowest number wins) -> a target policy of the given domain.
--  assignment_rule_assignments  the effective-dated RECORD of what the rules assigned to each
--                               employee: re-evaluated whenever the employee's attributes or the
--                               rules change; a row is closed (effective_to) and a new one opened
--                               when the outcome changes, so history is never rewritten.
-- An explicit per-employee allocation always overrides a rule - that precedence lives in each
-- domain's resolver, not here.

CREATE TABLE assignment_rules (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    domain           VARCHAR(30)  NOT NULL CHECK (domain IN ('PENALIZATION', 'LEAVE_PLAN', 'WFH_POLICY')),
    target_id        UUID         NOT NULL,   -- the policy/plan id within the domain (polymorphic, no FK)
    name             VARCHAR(150) NOT NULL,
    priority         INTEGER      NOT NULL,   -- evaluated lowest first; first matching rule wins
    department_id    UUID         REFERENCES departments(id),
    location_id      UUID         REFERENCES locations(id),
    shift_id         UUID         REFERENCES shifts(id),
    employment_type  VARCHAR(30),
    grade            VARCHAR(100),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by       UUID         REFERENCES users(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_assignment_rule_has_criterion CHECK (
        department_id IS NOT NULL OR location_id IS NOT NULL OR shift_id IS NOT NULL
        OR employment_type IS NOT NULL OR grade IS NOT NULL)
);
CREATE INDEX idx_assignment_rules_domain ON assignment_rules(domain, priority);

CREATE TABLE assignment_rule_assignments (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    domain            VARCHAR(30)  NOT NULL,
    employee_user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_id         UUID         NOT NULL,
    rule_id           UUID         REFERENCES assignment_rules(id) ON DELETE SET NULL,
    rule_name         VARCHAR(150) NOT NULL,  -- snapshot, so the explanation survives a rule rename/delete
    effective_from    DATE         NOT NULL,
    effective_to      DATE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX idx_rule_assignments_employee ON assignment_rule_assignments(domain, employee_user_id, effective_from);
