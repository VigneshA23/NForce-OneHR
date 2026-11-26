-- Overtime: attendance-driven calculation, rate rules, adjustments with audit trail, payroll link.
-- Additive and idempotent.

-- New permission overtime:CONFIGURE (rates, settings, adjustment approval) - ALL scope only.
INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('overtime', 'CONFIGURE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code
ON CONFLICT (module_id, action) DO NOTHING;

-- Granted to the two admin system roles only; custom roles must be given it explicitly.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('HR_ADMIN',    'overtime', 'CONFIGURE', 'ALL'),
    ('SUPER_ADMIN', 'overtime', 'CONFIGURE', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE permissions p SET min_scope = v.min_scope, max_scope = v.max_scope
FROM (VALUES
    ('overtime', 'CONFIGURE', 'ALL', 'ALL')
) AS v(module_code, action, min_scope, max_scope)
JOIN modules mo ON mo.code = v.module_code
WHERE p.module_id = mo.id AND p.action = v.action;

-- Singleton settings row.
CREATE TABLE IF NOT EXISTS ot_settings (
    id                          SMALLINT PRIMARY KEY CHECK (id = 1),
    min_ot_minutes              INT           NOT NULL DEFAULT 30,
    rounding_minutes            INT           NOT NULL DEFAULT 15,
    working_days_per_month      NUMERIC(5,2)  NOT NULL DEFAULT 26,
    require_approved_request    BOOLEAN       NOT NULL DEFAULT FALSE,
    max_request_minutes         INT           NOT NULL DEFAULT 720,
    allow_duplicate_requests    BOOLEAN       NOT NULL DEFAULT FALSE,
    adjustment_approval_required BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_by                  UUID,
    updated_at                  TIMESTAMP     NOT NULL DEFAULT NOW()
);
INSERT INTO ot_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS ot_rate_rules (
    id              UUID PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL,
    day_type        VARCHAR(20)   NOT NULL DEFAULT 'ANY',
    department_id   UUID,
    shift_id        UUID,
    multiplier      NUMERIC(5,2)  NOT NULL,
    effective_from  DATE          NOT NULL,
    effective_to    DATE,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by      UUID,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ot_rate_day_type CHECK (day_type IN ('ANY','WEEKDAY','WEEKLY_OFF','HOLIDAY')),
    CONSTRAINT chk_ot_rate_multiplier CHECK (multiplier > 0)
);
CREATE INDEX IF NOT EXISTS idx_ot_rate_rules_eff ON ot_rate_rules (effective_from);

-- Default rules so calculation works out of the box (1.5x weekday, 2x weekly off / holiday).
INSERT INTO ot_rate_rules (id, name, day_type, multiplier, effective_from)
SELECT gen_random_uuid(), v.name, v.day_type, v.mult, DATE '2000-01-01'
FROM (VALUES ('Default weekday OT', 'WEEKDAY', 1.5), ('Default weekly-off OT', 'WEEKLY_OFF', 2.0),
             ('Default holiday OT', 'HOLIDAY', 2.0)) AS v(name, day_type, mult)
WHERE NOT EXISTS (SELECT 1 FROM ot_rate_rules);

CREATE TABLE IF NOT EXISTS ot_calculations (
    id                  UUID PRIMARY KEY,
    employee_user_id    UUID          NOT NULL,
    work_date           DATE          NOT NULL,
    shift_id            UUID,
    shift_name          VARCHAR(100),
    scheduled_minutes   INT           NOT NULL,
    worked_minutes      INT           NOT NULL,
    ot_minutes          INT           NOT NULL,
    day_type            VARCHAR(20)   NOT NULL,
    rate_rule_id        UUID,
    multiplier          NUMERIC(5,2)  NOT NULL,
    hourly_rate         NUMERIC(12,4) NOT NULL,
    ot_amount           NUMERIC(12,2) NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'CALCULATED',
    adjusted            BOOLEAN       NOT NULL DEFAULT FALSE,
    ot_request_id       UUID,
    reviewed_by         UUID,
    reviewed_at         TIMESTAMP,
    review_comment      TEXT,
    payroll_run_id      UUID REFERENCES payroll_runs(id) ON DELETE SET NULL,
    calculated_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ot_calc_employee_date UNIQUE (employee_user_id, work_date),
    CONSTRAINT chk_ot_calc_status CHECK (status IN ('CALCULATED','APPROVED','REJECTED'))
);
CREATE INDEX IF NOT EXISTS idx_ot_calc_date ON ot_calculations (work_date);
CREATE INDEX IF NOT EXISTS idx_ot_calc_run ON ot_calculations (payroll_run_id);

CREATE TABLE IF NOT EXISTS ot_calculation_history (
    id              UUID PRIMARY KEY,
    calculation_id  UUID          NOT NULL REFERENCES ot_calculations(id) ON DELETE CASCADE,
    event           VARCHAR(30)   NOT NULL,
    worked_minutes  INT,
    ot_minutes      INT,
    multiplier      NUMERIC(5,2),
    hourly_rate     NUMERIC(12,4),
    ot_amount       NUMERIC(12,2),
    status          VARCHAR(20),
    note            TEXT,
    actor_id        UUID,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ot_calc_hist_calc ON ot_calculation_history (calculation_id);

CREATE TABLE IF NOT EXISTS ot_adjustments (
    id                UUID PRIMARY KEY,
    calculation_id    UUID          NOT NULL REFERENCES ot_calculations(id) ON DELETE CASCADE,
    original_minutes  INT           NOT NULL,
    original_amount   NUMERIC(12,2) NOT NULL,
    new_minutes       INT           NOT NULL,
    new_amount        NUMERIC(12,2) NOT NULL,
    reason            TEXT          NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    requested_by      UUID          NOT NULL,
    requested_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    decided_by        UUID,
    decided_at        TIMESTAMP,
    decision_comment  TEXT,
    CONSTRAINT chk_ot_adj_status CHECK (status IN ('PENDING','APPLIED','REJECTED'))
);
CREATE INDEX IF NOT EXISTS idx_ot_adj_calc ON ot_adjustments (calculation_id);
