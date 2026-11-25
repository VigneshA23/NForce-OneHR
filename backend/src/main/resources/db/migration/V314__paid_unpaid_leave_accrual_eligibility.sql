-- Paid / Unpaid leave, monthly or all-at-once crediting, and who each leave type applies to.
--
-- What this does (one transaction; re-running it is a no-op):
--   1. Snapshots leave_types, leave_balances and leave_carry_forward_transfers (*_pre_v314; never read by the app).
--   2. Schema:
--      - leave_types.system_key: 'PAID' / 'UNPAID' marks the two built-in types (can't be deleted, deactivated
--        or reclassified; can be renamed and restricted).
--      - leave_entitlement_policies.credit_frequency: YEARLY (all at once) or MONTHLY (quota / 12 on the 1st),
--        versioned by effective_year like the days.
--      - leave_requests.original_leave_type_name: the type a request had before this merge ("Sick Leave").
--      - leave_accrual_credits: one row per (employee, type, year, month) credited. leave_balances.total_days
--        is now "credited so far"; the UNIQUE key makes crediting safe to repeat.
--      - leave_type_eligibility_rules / leave_type_employee_overrides: who a leave type applies to.
--   3. Every existing leave type except per-occurrence / Maternity types is merged into "Paid Leave" (paid
--      types) or "Unpaid Leave" (unpaid types). Existing PAID / UNPAID-coded types are reused when suitable.
--      - Requests keep their dates, status and history; only the type changes, the old name is kept.
--      - Balances of the same employee and year are SUMMED into the Paid row, so nobody gains or loses days.
--        Carried-forward transfers are summed the same way.
--      - Penalty leave_breakdown JSON keys, penalization leave_priority_order codes and payroll review
--        selections are remapped. The old types are then deleted.
--   4. Balance rows of the current and earlier leave years are marked fully credited (0-day MIGRATION
--      ledger rows), so crediting never adds days on top of a migrated balance. Future-year rows restart
--      at 0 credited and are credited under the new policy.
--   5. Paid Leave policy: 15 days YEARLY up to the current year, 15 days MONTHLY from next year.
--   6. Maternity applies to Female employees only.
--
-- Rollback: restore leave_types, leave_balances and leave_carry_forward_transfers from the *_pre_v314
-- snapshots, repoint leave_requests by original_leave_type_name, drop the new tables and columns.

-- 1 ─────────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS leave_types_pre_v314 AS SELECT * FROM leave_types;
CREATE TABLE IF NOT EXISTS leave_balances_pre_v314 AS SELECT * FROM leave_balances;
CREATE TABLE IF NOT EXISTS leave_carry_forward_transfers_pre_v314 AS SELECT * FROM leave_carry_forward_transfers;

-- 2 ─────────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE leave_types ADD COLUMN IF NOT EXISTS system_key VARCHAR(10);
CREATE UNIQUE INDEX IF NOT EXISTS uq_leave_types_system_key ON leave_types (system_key) WHERE system_key IS NOT NULL;

ALTER TABLE leave_entitlement_policies
    ADD COLUMN IF NOT EXISTS credit_frequency VARCHAR(10) NOT NULL DEFAULT 'YEARLY';

ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS original_leave_type_name VARCHAR(50);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_leave_entitlement_credit_frequency') THEN
        ALTER TABLE leave_entitlement_policies ADD CONSTRAINT chk_leave_entitlement_credit_frequency
            CHECK (credit_frequency IN ('YEARLY', 'MONTHLY'));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS leave_accrual_credits (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    leave_type_id    UUID         NOT NULL REFERENCES leave_types(id) ON DELETE CASCADE,
    year             INTEGER      NOT NULL,
    month            INTEGER      NOT NULL CHECK (month BETWEEN 1 AND 12),
    days             NUMERIC(5,2) NOT NULL,
    source           VARCHAR(20)  NOT NULL CHECK (source IN ('ACCRUAL', 'MIGRATION')),
    credited_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (employee_user_id, leave_type_id, year, month)
);
CREATE INDEX IF NOT EXISTS idx_leave_accrual_credits_type_year ON leave_accrual_credits (leave_type_id, year);

CREATE TABLE IF NOT EXISTS leave_type_eligibility_rules (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    leave_type_id UUID         NOT NULL REFERENCES leave_types(id) ON DELETE CASCADE,
    rule_type     VARCHAR(20)  NOT NULL CHECK (rule_type IN ('GENDER', 'DEPARTMENT', 'LOCATION', 'EMPLOYMENT_TYPE')),
    value         VARCHAR(100) NOT NULL,
    UNIQUE (leave_type_id, rule_type, value)
);

CREATE TABLE IF NOT EXISTS leave_type_employee_overrides (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    leave_type_id    UUID        NOT NULL REFERENCES leave_types(id) ON DELETE CASCADE,
    employee_user_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mode             VARCHAR(10) NOT NULL CHECK (mode IN ('INCLUDE', 'EXCLUDE')),
    UNIQUE (leave_type_id, employee_user_id)
);

-- 3, 4, 5 ───────────────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE
    yr        INT := EXTRACT(YEAR FROM (now() AT TIME ZONE 'Asia/Kolkata'))::INT;
    paid_id   UUID;
    unpaid_id UUID;
BEGIN
    -- The two system types: reuse an existing suitable PAID / UNPAID-coded type, else create one.
    SELECT id INTO paid_id FROM leave_types WHERE system_key = 'PAID';
    IF paid_id IS NULL THEN
        SELECT id INTO paid_id FROM leave_types
        WHERE upper(code) = 'PAID' AND classification = 'PAID' AND entitlement_basis = 'ANNUAL';
        IF paid_id IS NULL THEN
            INSERT INTO leave_types (code, name, classification, entitlement_basis)
            VALUES (CASE WHEN EXISTS (SELECT 1 FROM leave_types WHERE upper(code) = 'PAID') THEN 'PAID_LEAVE' ELSE 'PAID' END,
                    'Paid Leave', 'PAID', 'ANNUAL')
            RETURNING id INTO paid_id;
        END IF;
        UPDATE leave_types SET system_key = 'PAID', active = TRUE WHERE id = paid_id;
    END IF;

    SELECT id INTO unpaid_id FROM leave_types WHERE system_key = 'UNPAID';
    IF unpaid_id IS NULL THEN
        SELECT id INTO unpaid_id FROM leave_types WHERE upper(code) = 'UNPAID' AND classification = 'UNPAID';
        IF unpaid_id IS NULL THEN
            INSERT INTO leave_types (code, name, classification, entitlement_basis)
            VALUES (CASE WHEN EXISTS (SELECT 1 FROM leave_types WHERE upper(code) = 'UNPAID') THEN 'UNPAID_LEAVE' ELSE 'UNPAID' END,
                    'Unpaid Leave', 'UNPAID', 'ANNUAL')
            RETURNING id INTO unpaid_id;
        END IF;
        UPDATE leave_types SET system_key = 'UNPAID', active = TRUE, carry_forward_enabled = FALSE WHERE id = unpaid_id;
    END IF;

    -- old type -> new type. Per-occurrence and Maternity types stay as they are.
    CREATE TEMP TABLE v314_type_map ON COMMIT DROP AS
    SELECT lt.id AS old_id, lt.code AS old_code, lt.name AS old_name,
           CASE WHEN lt.classification = 'PAID' THEN paid_id ELSE unpaid_id END AS new_id,
           lt.classification = 'PAID' AS paid
    FROM leave_types lt
    WHERE lt.system_key IS NULL
      AND NOT (lt.classification = 'PAID'
               AND (lt.entitlement_basis = 'PER_OCCURRENCE' OR upper(lt.code) = 'MATERNITY' OR lower(lt.name) LIKE 'maternity%'));

    -- Paid Leave inherits the old Annual Leave carry-forward settings when it has none of its own.
    UPDATE leave_types p SET carry_forward_enabled = a.carry_forward_enabled,
                             carry_forward_max_days = a.carry_forward_max_days,
                             carry_forward_expiry_months = a.carry_forward_expiry_months,
                             carry_forward_priority = a.carry_forward_priority
    FROM leave_types a JOIN v314_type_map m ON m.old_id = a.id
    WHERE p.id = paid_id AND upper(a.code) = 'ANNUAL' AND a.carry_forward_enabled AND NOT p.carry_forward_enabled;

    -- Requests: keep the old name for history, then repoint.
    UPDATE leave_requests r SET original_leave_type_name = COALESCE(r.original_leave_type_name, m.old_name),
                                leave_type_id = m.new_id
    FROM v314_type_map m WHERE r.leave_type_id = m.old_id;

    -- Balances: sum every old paid type into the Paid row of the same employee and year.
    INSERT INTO leave_balances (employee_user_id, leave_type_id, year, total_days, used_days,
                                carried_forward_days, carried_forward_used_days, carried_forward_expired_days,
                                carried_forward_expires_on)
    SELECT lb.employee_user_id, paid_id, lb.year, SUM(lb.total_days), SUM(lb.used_days),
           SUM(lb.carried_forward_days), SUM(lb.carried_forward_used_days), SUM(lb.carried_forward_expired_days),
           MAX(lb.carried_forward_expires_on)
    FROM leave_balances lb JOIN v314_type_map m ON m.old_id = lb.leave_type_id AND m.paid
    GROUP BY lb.employee_user_id, lb.year
    ON CONFLICT (employee_user_id, leave_type_id, year) DO UPDATE SET
        total_days = leave_balances.total_days + EXCLUDED.total_days,
        used_days = leave_balances.used_days + EXCLUDED.used_days,
        carried_forward_days = leave_balances.carried_forward_days + EXCLUDED.carried_forward_days,
        carried_forward_used_days = leave_balances.carried_forward_used_days + EXCLUDED.carried_forward_used_days,
        carried_forward_expired_days = leave_balances.carried_forward_expired_days + EXCLUDED.carried_forward_expired_days,
        carried_forward_expires_on = GREATEST(leave_balances.carried_forward_expires_on, EXCLUDED.carried_forward_expires_on),
        version = COALESCE(leave_balances.version, 0) + 1,
        updated_at = now();
    -- Unpaid balance rows never meant anything (unpaid is unlimited); merged paid rows are now in Paid.
    DELETE FROM leave_balances lb USING v314_type_map m WHERE lb.leave_type_id = m.old_id;

    INSERT INTO leave_carry_forward_transfers (employee_user_id, leave_type_id, from_year, unused_days, carried_days,
                                               lapsed_days, expires_on, processed_at, updated_at)
    SELECT t.employee_user_id, paid_id, t.from_year, SUM(t.unused_days), SUM(t.carried_days), SUM(t.lapsed_days),
           MAX(t.expires_on), MIN(t.processed_at), now()
    FROM leave_carry_forward_transfers t JOIN v314_type_map m ON m.old_id = t.leave_type_id AND m.paid
    GROUP BY t.employee_user_id, t.from_year
    ON CONFLICT (employee_user_id, leave_type_id, from_year) DO UPDATE SET
        unused_days = leave_carry_forward_transfers.unused_days + EXCLUDED.unused_days,
        carried_days = leave_carry_forward_transfers.carried_days + EXCLUDED.carried_days,
        lapsed_days = leave_carry_forward_transfers.lapsed_days + EXCLUDED.lapsed_days,
        expires_on = GREATEST(leave_carry_forward_transfers.expires_on, EXCLUDED.expires_on),
        updated_at = now();
    DELETE FROM leave_carry_forward_transfers t USING v314_type_map m WHERE t.leave_type_id = m.old_id;

    -- Occurrences only exist for per-occurrence types; anything left on a merged type is meaningless.
    DELETE FROM leave_occurrences o USING v314_type_map m WHERE o.leave_type_id = m.old_id;

    UPDATE payroll_run_review_items i SET selected_leave_type_id = m.new_id
    FROM v314_type_map m WHERE i.selected_leave_type_id = m.old_id;

    -- Penalty breakdowns are JSON keyed by type code: merge old keys into the new code, summing days.
    UPDATE attendance_penalties p SET leave_breakdown = sub.j
    FROM (
        SELECT x.id, jsonb_object_agg(x.k, x.v)::text AS j
        FROM (
            SELECT p2.id, COALESCE(nt.code, kv.key) AS k, SUM(kv.value::NUMERIC) AS v
            FROM attendance_penalties p2
            CROSS JOIN LATERAL jsonb_each_text(p2.leave_breakdown::jsonb) kv
            LEFT JOIN v314_type_map m ON m.old_code = kv.key
            LEFT JOIN leave_types nt ON nt.id = m.new_id
            WHERE p2.leave_breakdown IS NOT NULL AND btrim(p2.leave_breakdown) <> ''
              AND EXISTS (SELECT 1 FROM jsonb_object_keys(p2.leave_breakdown::jsonb) k
                          JOIN v314_type_map m2 ON m2.old_code = k)
            GROUP BY p2.id, COALESCE(nt.code, kv.key)
        ) x
        GROUP BY x.id
    ) sub
    WHERE p.id = sub.id;

    -- Penalization priority order (comma-separated codes): old paid codes become Paid, deduplicated in
    -- first-seen order; unpaid codes are dropped (unpaid leave can't absorb a penalty).
    UPDATE penalization_policy_versions v SET leave_priority_order = NULLIF(sub.o, '')
    FROM (
        SELECT v2.id,
               COALESCE(string_agg(x.code, ',' ORDER BY x.pos) FILTER (WHERE x.code IS NOT NULL), '') AS o
        FROM penalization_policy_versions v2
        LEFT JOIN LATERAL (
            SELECT c.code, MIN(t.pos) AS pos
            FROM unnest(string_to_array(replace(v2.leave_priority_order, ' ', ''), ',')) WITH ORDINALITY AS t(tok, pos)
            LEFT JOIN v314_type_map m ON m.old_code = t.tok
            LEFT JOIN leave_types nt ON nt.id = m.new_id
            CROSS JOIN LATERAL (SELECT CASE WHEN t.tok = '' THEN NULL
                                            WHEN m.old_id IS NULL THEN t.tok
                                            WHEN m.paid THEN nt.code
                                            ELSE NULL END AS code) c
            GROUP BY c.code
        ) x ON TRUE
        WHERE v2.leave_priority_order IS NOT NULL
          AND EXISTS (SELECT 1 FROM unnest(string_to_array(replace(v2.leave_priority_order, ' ', ''), ',')) tok
                      JOIN v314_type_map m3 ON m3.old_code = tok)
        GROUP BY v2.id
    ) sub
    WHERE v.id = sub.id;

    DELETE FROM leave_types lt USING v314_type_map m WHERE lt.id = m.old_id; -- policies and rules cascade

    -- 4: existing balances of this and earlier years are already fully credited; later years start at 0.
    INSERT INTO leave_accrual_credits (employee_user_id, leave_type_id, year, month, days, source)
    SELECT lb.employee_user_id, lb.leave_type_id, lb.year, mo.mon, 0, 'MIGRATION'
    FROM leave_balances lb
    JOIN leave_types lt ON lt.id = lb.leave_type_id AND lt.classification = 'PAID' AND lt.entitlement_basis = 'ANNUAL'
    CROSS JOIN generate_series(1, 12) AS mo(mon)
    WHERE lb.year <= yr
    ON CONFLICT (employee_user_id, leave_type_id, year, month) DO NOTHING;

    UPDATE leave_balances lb SET total_days = 0, version = COALESCE(lb.version, 0) + 1, updated_at = now()
    FROM leave_types lt
    WHERE lt.id = lb.leave_type_id AND lt.classification = 'PAID' AND lt.entitlement_basis = 'ANNUAL'
      AND lb.year > yr AND lb.total_days <> 0
      AND NOT EXISTS (SELECT 1 FROM leave_accrual_credits c
                      WHERE c.employee_user_id = lb.employee_user_id AND c.leave_type_id = lb.leave_type_id AND c.year = lb.year);

    -- 5: Paid Leave policy.
    INSERT INTO leave_entitlement_policies (leave_type_id, effective_year, days, credit_frequency)
    VALUES (paid_id, 2000, 15, 'YEARLY'), (paid_id, yr + 1, 15, 'MONTHLY')
    ON CONFLICT (leave_type_id, effective_year) DO NOTHING;
    DELETE FROM leave_entitlement_policies WHERE leave_type_id = unpaid_id;
END $$;

-- 6 ─────────────────────────────────────────────────────────────────────────────────────────────
INSERT INTO leave_type_eligibility_rules (leave_type_id, rule_type, value)
SELECT id, 'GENDER', 'Female' FROM leave_types
WHERE classification = 'PAID' AND (upper(code) = 'MATERNITY' OR lower(name) LIKE 'maternity%')
ON CONFLICT (leave_type_id, rule_type, value) DO NOTHING;
