-- Configurable leave types, entitlements and carry-forward.
--
-- What this does (additive, idempotent, one transaction):
--   1. Snapshots leave_balances into leave_balances_pre_v306 (rollback/reference only; never read by the app).
--   2. leave_types: active flag, entitlement basis (ANNUAL / PER_OCCURRENCE), carry-forward settings.
--      Unpaid types are always unlimited (derived from classification, nothing stored).
--   3. leave_entitlement_policies: entitlement days per type with an effective year. Seeded with the
--      defaults as configuration (effective 2000 = applies to every year until changed):
--      regular paid 15 days/year, Maternity 182 days per occurrence. Unpaid gets no row (unlimited).
--   4. leave_balances: a separate carried-forward bucket (days, used, expired, expiry date).
--   5. leave_carry_forward_transfers: year-end ledger, UNIQUE (employee, type, from_year) = no duplicate transfer.
--   6. leave_occurrences: Maternity-style per-occurrence entitlement (not a yearly balance).
--   7. RESET of the current leave year (Asia/Kolkata, the business-zone default): every employee gets one
--      independent row per regular paid type = policy entitlement, used = that type's OWN approved leave in
--      the year + active attendance-penalty deductions of that type. The old Annual/Sick/Casual consolidated
--      figures are discarded on purpose. Late-arrival half-day Casual deductions (LatePenaltyService, never
--      recorded per request) are not reconstructed. Unpaid and per-occurrence balance rows for the current
--      and later years are removed (unpaid is unlimited; occurrences live in leave_occurrences).
--      Past-year balance rows and every leave request are left untouched.
--   8. Permission leave_configuration under Organization > Organization Structure (VIEW/EDIT/CONFIGURE).
--      HR_ADMIN/SUPER_ADMIN get all three; any other role that held leave:CONFIGURE:ALL keeps the same
--      power (VIEW + CONFIGURE). leave:CONFIGURE is then retired from the leave module.
--      retire-permission: leave:CONFIGURE
--
-- Rollback: restore leave_balances from leave_balances_pre_v306 (same columns + the new ones defaulted),
--           drop the three new tables and the new columns, re-register leave CONFIGURE.

-- 1 ─────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS leave_balances_pre_v306 AS SELECT * FROM leave_balances;

-- 2 ─────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE leave_types
    ADD COLUMN IF NOT EXISTS active                      BOOLEAN      NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS entitlement_basis           VARCHAR(20)  NOT NULL DEFAULT 'ANNUAL',
    ADD COLUMN IF NOT EXISTS occurrence_window_months    INTEGER,
    ADD COLUMN IF NOT EXISTS carry_forward_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS carry_forward_max_days      NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS carry_forward_expiry_months INTEGER,
    ADD COLUMN IF NOT EXISTS carry_forward_priority      VARCHAR(30)  NOT NULL DEFAULT 'CARRY_FORWARD_FIRST';

UPDATE leave_types SET entitlement_basis = 'PER_OCCURRENCE', occurrence_window_months = COALESCE(occurrence_window_months, 12)
WHERE classification = 'PAID' AND (code = 'MATERNITY' OR lower(name) LIKE 'maternity%');

INSERT INTO leave_types (code, name, classification, entitlement_basis, occurrence_window_months)
SELECT 'MATERNITY', 'Maternity Leave', 'PAID', 'PER_OCCURRENCE', 12
WHERE NOT EXISTS (SELECT 1 FROM leave_types WHERE entitlement_basis = 'PER_OCCURRENCE')
ON CONFLICT (code) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_leave_types_entitlement_config') THEN
        ALTER TABLE leave_types ADD CONSTRAINT chk_leave_types_entitlement_config CHECK (
            entitlement_basis IN ('ANNUAL', 'PER_OCCURRENCE')
            AND carry_forward_priority IN ('CARRY_FORWARD_FIRST', 'CURRENT_FIRST')
            AND (carry_forward_max_days IS NULL OR carry_forward_max_days >= 0)
            AND (carry_forward_expiry_months IS NULL OR carry_forward_expiry_months > 0)
            AND (occurrence_window_months IS NULL OR occurrence_window_months > 0)
            -- carry-forward exists only for regular paid (annual) types: never unpaid, never maternity
            AND (NOT carry_forward_enabled OR (classification = 'PAID' AND entitlement_basis = 'ANNUAL'))
        );
    END IF;
END $$;

-- 3 ─────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS leave_entitlement_policies (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    leave_type_id  UUID         NOT NULL REFERENCES leave_types(id) ON DELETE CASCADE,
    effective_year INTEGER      NOT NULL,
    days           NUMERIC(5,2) NOT NULL CHECK (days >= 0),
    created_by     UUID,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (leave_type_id, effective_year)
);

INSERT INTO leave_entitlement_policies (leave_type_id, effective_year, days)
SELECT id, 2000, CASE WHEN entitlement_basis = 'PER_OCCURRENCE' THEN 182 ELSE 15 END
FROM leave_types WHERE classification = 'PAID'
ON CONFLICT (leave_type_id, effective_year) DO NOTHING;

-- 4 ─────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE leave_balances
    ADD COLUMN IF NOT EXISTS carried_forward_days         NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS carried_forward_used_days    NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS carried_forward_expired_days NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS carried_forward_expires_on   DATE;

-- 5 ─────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS leave_carry_forward_transfers (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    leave_type_id    UUID         NOT NULL REFERENCES leave_types(id),
    from_year        INTEGER      NOT NULL,
    unused_days      NUMERIC(5,2) NOT NULL,
    carried_days     NUMERIC(5,2) NOT NULL,
    lapsed_days      NUMERIC(5,2) NOT NULL,
    expires_on       DATE,
    processed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (employee_user_id, leave_type_id, from_year)
);
CREATE INDEX IF NOT EXISTS idx_leave_cf_transfers_year ON leave_carry_forward_transfers (from_year);

-- 6 ─────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS leave_occurrences (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    leave_type_id    UUID         NOT NULL REFERENCES leave_types(id),
    start_date       DATE         NOT NULL,
    window_end_date  DATE         NOT NULL,
    entitlement_days NUMERIC(5,2) NOT NULL CHECK (entitlement_days >= 0),
    used_days        NUMERIC(5,2) NOT NULL DEFAULT 0,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (employee_user_id, leave_type_id, start_date),
    CHECK (window_end_date >= start_date)
);

-- 7 ─────────────────────────────────────────────────────────────────────────────────────────────
DELETE FROM leave_balances lb USING leave_types lt
WHERE lt.id = lb.leave_type_id
  AND (lt.classification = 'UNPAID' OR lt.entitlement_basis = 'PER_OCCURRENCE')
  AND lb.year >= EXTRACT(YEAR FROM (now() AT TIME ZONE 'Asia/Kolkata'))::INT;

WITH y AS (
    SELECT EXTRACT(YEAR FROM (now() AT TIME ZONE 'Asia/Kolkata'))::INT AS yr
), approved AS (
    -- days of each approved request that fall inside the year (a request may span two years)
    SELECT r.employee_user_id, r.leave_type_id,
           SUM(CASE WHEN r.is_half_day THEN r.total_days
                    ELSE (LEAST(r.end_date, make_date(y.yr, 12, 31)) - GREATEST(r.start_date, make_date(y.yr, 1, 1)) + 1)
               END) AS days
    FROM leave_requests r CROSS JOIN y
    WHERE r.status = 'APPROVED' AND r.start_date <= make_date(y.yr, 12, 31) AND r.end_date >= make_date(y.yr, 1, 1)
    GROUP BY r.employee_user_id, r.leave_type_id
), penalties AS (
    SELECT p.employee_user_id, lt.id AS leave_type_id, SUM(kv.value::NUMERIC) AS days
    FROM attendance_penalties p
    CROSS JOIN y
    CROSS JOIN LATERAL jsonb_each_text(p.leave_breakdown::jsonb) kv
    JOIN leave_types lt ON lt.code = kv.key
    WHERE p.status = 'PENDING_REVIEW' AND p.leave_breakdown IS NOT NULL AND btrim(p.leave_breakdown) <> ''
      AND EXTRACT(YEAR FROM p.incident_date)::INT = y.yr
    GROUP BY p.employee_user_id, lt.id
)
INSERT INTO leave_balances (employee_user_id, leave_type_id, year, total_days, used_days)
SELECT e.user_id, lt.id, y.yr, pol.days, COALESCE(a.days, 0) + COALESCE(pe.days, 0)
FROM employees e
CROSS JOIN leave_types lt
CROSS JOIN y
JOIN leave_entitlement_policies pol ON pol.leave_type_id = lt.id
     AND pol.effective_year = (SELECT MAX(p2.effective_year) FROM leave_entitlement_policies p2
                               WHERE p2.leave_type_id = lt.id AND p2.effective_year <= y.yr)
LEFT JOIN approved a   ON a.employee_user_id = e.user_id AND a.leave_type_id = lt.id
LEFT JOIN penalties pe ON pe.employee_user_id = e.user_id AND pe.leave_type_id = lt.id
WHERE lt.classification = 'PAID' AND lt.entitlement_basis = 'ANNUAL'
ON CONFLICT (employee_user_id, leave_type_id, year) DO UPDATE SET
    total_days = EXCLUDED.total_days,
    used_days = EXCLUDED.used_days,
    carried_forward_days = 0,
    carried_forward_used_days = 0,
    carried_forward_expired_days = 0,
    carried_forward_expires_on = NULL,
    version = COALESCE(leave_balances.version, 0) + 1,
    updated_at = now();

-- 8 ─────────────────────────────────────────────────────────────────────────────────────────────
INSERT INTO modules (code, display_name, custom_role_ready) VALUES
    ('leave_configuration', 'Leave Configuration', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions (module_id, action)
SELECT m.id, v.action
FROM (VALUES
    ('leave_configuration', 'VIEW'),
    ('leave_configuration', 'EDIT'),
    ('leave_configuration', 'CONFIGURE')
) AS v(module_code, action)
JOIN modules m ON m.code = v.module_code
ON CONFLICT (module_id, action) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT r.id, p.id, v.scope
FROM (VALUES
    ('HR_ADMIN', 'leave_configuration', 'VIEW', 'ALL'),
    ('HR_ADMIN', 'leave_configuration', 'EDIT', 'ALL'),
    ('HR_ADMIN', 'leave_configuration', 'CONFIGURE', 'ALL'),
    ('SUPER_ADMIN', 'leave_configuration', 'VIEW', 'ALL'),
    ('SUPER_ADMIN', 'leave_configuration', 'EDIT', 'ALL'),
    ('SUPER_ADMIN', 'leave_configuration', 'CONFIGURE', 'ALL')
) AS v(role_code, module_code, action, scope)
JOIN roles r ON r.code = v.role_code
JOIN modules m ON m.code = v.module_code
JOIN permissions p ON p.module_id = m.id AND p.action = v.action
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Whoever could manage leave types through the old leave CONFIGURE grant keeps exactly that power
-- (view + configure); manual balance adjustment (EDIT) is new and is not handed out implicitly.
INSERT INTO role_permissions (role_id, permission_id, scope)
SELECT rp.role_id, np.id, 'ALL'
FROM role_permissions rp
JOIN permissions op ON op.id = rp.permission_id AND op.action = 'CONFIGURE'
JOIN modules om ON om.id = op.module_id AND om.code = 'leave'
JOIN modules nm ON nm.code = 'leave_configuration'
JOIN permissions np ON np.module_id = nm.id AND np.action IN ('VIEW', 'CONFIGURE')
ON CONFLICT (role_id, permission_id) DO NOTHING;

DELETE FROM role_permissions rp USING permissions p, modules m
WHERE rp.permission_id = p.id AND p.module_id = m.id AND m.code = 'leave' AND p.action = 'CONFIGURE';
DELETE FROM permissions p USING modules m
WHERE p.module_id = m.id AND m.code = 'leave' AND p.action = 'CONFIGURE';

UPDATE modules m SET parent_id = p.id, node_type = v.node_type, sort_order = v.sort_order, sensitivity = v.sensitivity
FROM (VALUES
    ('leave_configuration', 'sub.org_structure', 'SECTION', 30, 'NORMAL')
) AS v(code, parent_code, node_type, sort_order, sensitivity)
LEFT JOIN modules p ON p.code = NULLIF(v.parent_code, '')
WHERE m.code = v.code;

UPDATE modules SET description = 'Leave types, entitlements, carry-forward and year-end processing. Edit adjusts an individual employee''s leave balance.'
WHERE code = 'leave_configuration';
UPDATE modules SET description = 'Team/organisation leave and approvals.'
WHERE code = 'leave';

UPDATE permissions p SET min_scope = v.min_scope, max_scope = v.max_scope
FROM (VALUES
    ('leave_configuration', 'VIEW', 'ALL', 'ALL'),
    ('leave_configuration', 'EDIT', 'ALL', 'ALL'),
    ('leave_configuration', 'CONFIGURE', 'ALL', 'ALL')
) AS v(module_code, action, min_scope, max_scope)
JOIN modules mo ON mo.code = v.module_code
WHERE p.module_id = mo.id AND p.action = v.action;
