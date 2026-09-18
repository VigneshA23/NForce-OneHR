-- Workflow Studio (Phase 1 — Expense amount-threshold rules): the visual rule builder that lets a
-- Super Admin configure, preview and activate/deactivate a data-driven approval rule instead of a
-- developer hardcoding it. See ApprovalRuleEvaluationService for how an active rule is consumed.
--
-- At most one rule may be active per request_type: activating a rule must first deactivate
-- whichever rule previously governed that type (see ApprovalRuleService#activate), and this index
-- makes that invariant impossible to violate even under a race between two admins.
CREATE TABLE approval_rules (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_name        VARCHAR(150)  NOT NULL,
    request_type     VARCHAR(30)   NOT NULL,
    condition_field  VARCHAR(50)   NOT NULL,
    operator         VARCHAR(5)    NOT NULL,
    condition_value  VARCHAR(50)   NOT NULL,
    approval_stages  VARCHAR(100)  NOT NULL,
    active           BOOLEAN       NOT NULL DEFAULT false,
    created_by       UUID          NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       UUID          REFERENCES users(id),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_approval_rules_one_active_per_type
    ON approval_rules (request_type)
    WHERE active = true;

CREATE INDEX idx_approval_rules_request_type ON approval_rules(request_type);
