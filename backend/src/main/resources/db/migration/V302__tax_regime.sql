-- US-F06: tax regime choice (Old/New) and the org-wide window in which it may be changed.
-- No row in employee_tax_regime = New Regime (the default). The window table holds one row (id = 1);
-- no row or null dates = window closed. Rollback: DROP TABLE employee_tax_regime, tax_regime_window;
CREATE TABLE IF NOT EXISTS employee_tax_regime (
    employee_user_id UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    regime           VARCHAR(3)  NOT NULL CHECK (regime IN ('OLD', 'NEW')),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tax_regime_window (
    id        SMALLINT PRIMARY KEY CHECK (id = 1),
    opens_on  DATE,
    closes_on DATE,
    CHECK (closes_on >= opens_on)
);
