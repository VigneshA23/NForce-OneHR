-- Comp-Off: eligibility from attendance, OT-vs-Comp-Off selection, credit ledger with expiry and audit,
-- and utilisation through the existing leave workflow (leave type COMP_OFF).
-- Additive and idempotent. Existing OT behaviour is unchanged: overtime stays enabled, comp-off disabled by default.

-- New permission node compensation_policy:CONFIGURE (Super Admin only): chooses OT and/or Comp-Off for the company.
INSERT INTO modules (code, display_name, custom_role_ready) VALUES
    ('compensation_policy', 'Compensation Policy', FALSE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('compensation_policy', 'CONFIGURE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code
ON CONFLICT (module_id, action) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('SUPER_ADMIN', 'compensation_policy', 'CONFIGURE', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE modules m SET parent_id = p.id, node_type = v.node_type, sort_order = v.sort_order, sensitivity = v.sensitivity
FROM (VALUES
    ('compensation_policy', 'mod.leave', 'SUBMODULE', 30, 'NORMAL')
) AS v(code, parent_code, node_type, sort_order, sensitivity)
LEFT JOIN modules p ON p.code = NULLIF(v.parent_code, '')
WHERE m.code = v.code;

UPDATE modules SET description = 'Company-wide choice between Overtime and Comp-Off for eligible extra working hours.'
WHERE code = 'compensation_policy';

UPDATE permissions p SET min_scope = v.min_scope, max_scope = v.max_scope
FROM (VALUES
    ('compensation_policy', 'CONFIGURE', 'ALL', 'ALL')
) AS v(module_code, action, min_scope, max_scope)
JOIN modules mo ON mo.code = v.module_code
WHERE p.module_id = mo.id AND p.action = v.action;

-- Singleton policy row (id 1).
CREATE TABLE IF NOT EXISTS comp_off_settings (
    id                        SMALLINT PRIMARY KEY CHECK (id = 1),
    overtime_enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    comp_off_enabled          BOOLEAN       NOT NULL DEFAULT FALSE,
    eligible_weekly_off       BOOLEAN       NOT NULL DEFAULT TRUE,
    eligible_holiday          BOOLEAN       NOT NULL DEFAULT TRUE,
    include_weekday_extra     BOOLEAN       NOT NULL DEFAULT FALSE,
    min_minutes_half_day      INT           NOT NULL DEFAULT 240,
    min_minutes_full_day      INT           NOT NULL DEFAULT 480,
    validity_days             INT           NOT NULL DEFAULT 90,
    require_credit_approval   BOOLEAN       NOT NULL DEFAULT FALSE,
    allow_partial_day         BOOLEAN       NOT NULL DEFAULT TRUE,
    expiry_notice_days        INT           NOT NULL DEFAULT 7,
    updated_by                UUID,
    updated_at                TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_comp_off_policy_one_enabled CHECK (overtime_enabled OR comp_off_enabled),
    CONSTRAINT chk_comp_off_half_full CHECK (min_minutes_half_day > 0 AND min_minutes_full_day >= min_minutes_half_day)
);
INSERT INTO comp_off_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

-- One row per employee and eligible working day: the OT-or-Comp-Off decision for those hours.
CREATE TABLE IF NOT EXISTS comp_off_claims (
    id                UUID PRIMARY KEY,
    employee_user_id  UUID          NOT NULL,
    work_date         DATE          NOT NULL,
    day_type          VARCHAR(20)   NOT NULL,
    worked_minutes    INT           NOT NULL,
    eligible_minutes  INT           NOT NULL,
    credit_days       NUMERIC(5,2)  NOT NULL,
    choice            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    chosen_at         TIMESTAMP,
    chosen_by         UUID,
    credit_id         UUID,
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_comp_off_claim_employee_date UNIQUE (employee_user_id, work_date),
    CONSTRAINT chk_comp_off_claim_choice CHECK (choice IN ('PENDING','OT','COMP_OFF')),
    CONSTRAINT chk_comp_off_claim_day_type CHECK (day_type IN ('WEEKDAY','WEEKLY_OFF','HOLIDAY'))
);
CREATE INDEX IF NOT EXISTS idx_comp_off_claims_date ON comp_off_claims (work_date);

CREATE TABLE IF NOT EXISTS comp_off_credits (
    id                UUID PRIMARY KEY,
    employee_user_id  UUID          NOT NULL,
    claim_id          UUID REFERENCES comp_off_claims(id) ON DELETE SET NULL,
    source            VARCHAR(20)   NOT NULL DEFAULT 'EARNED',
    work_date         DATE,
    credit_days       NUMERIC(5,2)  NOT NULL,
    used_days         NUMERIC(5,2)  NOT NULL DEFAULT 0,
    deducted_days     NUMERIC(5,2)  NOT NULL DEFAULT 0,
    expired_days      NUMERIC(5,2)  NOT NULL DEFAULT 0,
    status            VARCHAR(20)   NOT NULL,
    earned_on         DATE          NOT NULL,
    expires_on        DATE          NOT NULL,
    reason            TEXT,
    reviewed_by       UUID,
    reviewed_at       TIMESTAMP,
    review_comment    TEXT,
    expiry_notified   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP     NOT NULL DEFAULT NOW(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_comp_off_credit_source CHECK (source IN ('EARNED','MANUAL')),
    CONSTRAINT chk_comp_off_credit_status CHECK (status IN ('PENDING_APPROVAL','AVAILABLE','REJECTED','EXPIRED')),
    CONSTRAINT chk_comp_off_credit_days CHECK (credit_days > 0 AND used_days >= 0 AND deducted_days >= 0
                                               AND used_days + deducted_days + expired_days <= credit_days)
);
CREATE INDEX IF NOT EXISTS idx_comp_off_credits_emp ON comp_off_credits (employee_user_id, status);
CREATE INDEX IF NOT EXISTS idx_comp_off_credits_exp ON comp_off_credits (expires_on);
CREATE UNIQUE INDEX IF NOT EXISTS uq_comp_off_credit_claim ON comp_off_credits (claim_id) WHERE claim_id IS NOT NULL;

-- Which credits an approved leave request consumed (oldest expiry first).
CREATE TABLE IF NOT EXISTS comp_off_utilizations (
    id                UUID PRIMARY KEY,
    credit_id         UUID          NOT NULL REFERENCES comp_off_credits(id) ON DELETE CASCADE,
    leave_request_id  UUID          NOT NULL,
    days              NUMERIC(5,2)  NOT NULL,
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_comp_off_util_leave ON comp_off_utilizations (leave_request_id);

-- Audit history: generation, approval, adjustment, utilisation, expiry.
CREATE TABLE IF NOT EXISTS comp_off_events (
    id                UUID PRIMARY KEY,
    employee_user_id  UUID          NOT NULL,
    credit_id         UUID,
    event             VARCHAR(30)   NOT NULL,
    days              NUMERIC(5,2),
    note              TEXT,
    actor_id          UUID,
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_comp_off_events_emp ON comp_off_events (employee_user_id, created_at DESC);

-- Comp-Off is requested through the normal leave flow as this paid leave type. Its balance lives in
-- comp_off_credits, never in leave_balances.
INSERT INTO leave_types (code, name, classification)
VALUES ('COMP_OFF', 'Comp-Off', 'PAID')
ON CONFLICT (code) DO NOTHING;
