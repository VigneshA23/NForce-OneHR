-- NForce OneHR — Flyway Migration V21
-- Expense management: categories (with policy rules) and claims (two-stage approval).
--
-- Claim status lifecycle:
--   SUBMITTED         -> manager's Approval Center queue
--   MANAGER_APPROVED  -> HR Admin / Super Admin final-clearance queue
--   MANAGER_REJECTED  -> terminal; employee sees rejection reason
--   CLEARED_FOR_PAYROLL -> HR/SA cleared; awaiting Mark-as-Paid bookkeeping step
--   FINAL_REJECTED    -> terminal; employee sees rejection reason
--   PAID              -> HR manually confirmed payroll inclusion; terminal

CREATE TABLE IF NOT EXISTS expense_categories (
    id                     SERIAL        PRIMARY KEY,
    name                   VARCHAR(60)   NOT NULL UNIQUE,
    requires_receipt_above NUMERIC(10,2) NOT NULL DEFAULT 0,
    daily_limit            NUMERIC(10,2),
    second_approval_above  NUMERIC(10,2)
);

CREATE TABLE IF NOT EXISTS expense_claims (
    id                       UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id         UUID          NOT NULL REFERENCES users(id),
    category_id              INTEGER       NOT NULL REFERENCES expense_categories(id),
    amount                   NUMERIC(10,2) NOT NULL,
    expense_date             DATE          NOT NULL,
    business_purpose         TEXT          NOT NULL,
    -- TEXT (not VARCHAR) — receipt is stored as a base64 data: URI for local dev;
    -- replace with an S3/CDN URL when file storage is available.
    receipt_url              TEXT,
    status                   VARCHAR(30)   NOT NULL DEFAULT 'SUBMITTED',
    manager_decided_by       UUID          REFERENCES users(id),
    manager_decided_at       TIMESTAMPTZ,
    manager_rejection_reason TEXT,
    final_decided_by         UUID          REFERENCES users(id),
    final_decided_at         TIMESTAMPTZ,
    final_rejection_reason   TEXT,
    paid_at                  TIMESTAMPTZ,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_expense_claims_employee ON expense_claims(employee_user_id);
CREATE INDEX IF NOT EXISTS idx_expense_claims_status   ON expense_claims(status);

-- Starter categories. HR Admin can edit limits via the Policy Management UI.
INSERT INTO expense_categories (name, requires_receipt_above, daily_limit, second_approval_above) VALUES
    ('Travel',                  0.00, 2000.00,  500.00),
    ('Meals',                  25.00,  100.00,  100.00),
    ('Internet',                0.00,   50.00,     NULL),
    ('Certification/Training',  0.00, 5000.00, 1000.00),
    ('Office Supplies',        25.00,  200.00,     NULL),
    ('Software/Subscriptions',  0.00,  500.00,  200.00)
ON CONFLICT (name) DO NOTHING;
