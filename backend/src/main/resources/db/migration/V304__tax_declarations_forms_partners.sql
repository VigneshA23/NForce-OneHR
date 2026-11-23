-- US-F10..F14: declaration/proof windows, declarations + proofs, previous-employment income,
-- Form 130 publishing, tax partner links and ITR due date.
-- Rollback: DROP TABLE tax_proof, tax_declaration, tax_window, previous_employment, form130_publish,
--           tax_partner, tax_filing_config, employee_tax_regime_unlock;

-- One row per window kind. monthly = open only on days 1-22 of each month inside [opens_on, closes_on].
CREATE TABLE IF NOT EXISTS tax_window (
    kind      VARCHAR(12) PRIMARY KEY CHECK (kind IN ('DECLARATION', 'PROOF')),
    opens_on  DATE        NOT NULL,
    closes_on DATE        NOT NULL,
    monthly   BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS tax_declaration (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID          NOT NULL REFERENCES users(id),
    fy_start_year    INT           NOT NULL,
    category         VARCHAR(20)   NOT NULL CHECK (category IN ('SEC_80C', 'OTHER_DEDUCTIONS', 'ALLOWANCES', 'HOUSE_PROPERTY', 'OTHER_INCOME')),
    name             VARCHAR(120)  NOT NULL,
    amount           NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
    proof_status     VARCHAR(10)   NOT NULL DEFAULT 'NONE' CHECK (proof_status IN ('NONE', 'PENDING', 'ACCEPTED', 'PARTIAL', 'REJECTED')),
    accepted_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    review_comment   VARCHAR(500),
    reviewed_by      UUID REFERENCES users(id),
    reviewed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_tax_declaration_emp ON tax_declaration(employee_user_id, fy_start_year);

-- ponytail: proof files live in the table (<= 5 MB each, checked in the service); move to object storage if volume grows.
CREATE TABLE IF NOT EXISTS tax_proof (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    declaration_id UUID         NOT NULL REFERENCES tax_declaration(id) ON DELETE CASCADE,
    file_name      VARCHAR(255) NOT NULL,
    content_type   VARCHAR(100),
    data           BYTEA        NOT NULL,
    uploaded_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- US-F12: month-wise income from the previous employer. PENDING until a Super Admin approves (or enters it).
CREATE TABLE IF NOT EXISTS previous_employment (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID          NOT NULL REFERENCES users(id),
    fy_start_year    INT           NOT NULL,
    period           CHAR(7)       NOT NULL,            -- yyyy-MM
    gross            NUMERIC(14,2) NOT NULL DEFAULT 0,
    basic            NUMERIC(14,2) NOT NULL DEFAULT 0,
    hra              NUMERIC(14,2) NOT NULL DEFAULT 0,
    employee_pf      NUMERIC(14,2) NOT NULL DEFAULT 0,
    esi              NUMERIC(14,2) NOT NULL DEFAULT 0,
    lwf              NUMERIC(14,2) NOT NULL DEFAULT 0,
    lwf_employer     NUMERIC(14,2) NOT NULL DEFAULT 0,
    nps_employer     NUMERIC(14,2) NOT NULL DEFAULT 0,
    professional_tax NUMERIC(14,2) NOT NULL DEFAULT 0,
    income_tax       NUMERIC(14,2) NOT NULL DEFAULT 0,
    other_tax_free   NUMERIC(14,2) NOT NULL DEFAULT 0,
    other_taxable    NUMERIC(14,2) NOT NULL DEFAULT 0,
    status           VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED')),
    updated_by       UUID REFERENCES users(id),
    approved_by      UUID REFERENCES users(id),
    UNIQUE (employee_user_id, fy_start_year, period)
);

-- US-F13: a row means Form 130 is published for that FY.
CREATE TABLE IF NOT EXISTS form130_publish (
    fy_start_year INT PRIMARY KEY,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_by  UUID REFERENCES users(id)
);

-- US-F14: partner links the Super Admin chooses to show. No partners are seeded.
CREATE TABLE IF NOT EXISTS tax_partner (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        VARCHAR(7)   NOT NULL CHECK (kind IN ('FILING', 'SAVING')),
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    steps       VARCHAR(1000),
    url         VARCHAR(500) NOT NULL,
    coupon_code VARCHAR(60),
    featured    BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS tax_filing_config (
    fy_start_year INT PRIMARY KEY,
    itr_due_date  DATE NOT NULL
);

-- US-F06: Super Admin unlocks regime change for one employee outside the window; consumed on use.
CREATE TABLE IF NOT EXISTS employee_tax_regime_unlock (
    employee_user_id UUID PRIMARY KEY REFERENCES users(id),
    unlocked_by      UUID REFERENCES users(id),
    unlocked_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
