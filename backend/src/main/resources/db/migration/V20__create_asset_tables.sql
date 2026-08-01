-- NForce OneHR — Flyway Migration V20
-- Asset master data: categories, inventory, assignments (effective-dated), requests.
--
-- Assignment history is ALWAYS append-only:
--   current assignment = effective_to IS NULL
--   Reassign = close current row (effective_to = now()), insert new row
--   NEVER update employee_user_id in place on an existing row

CREATE TABLE IF NOT EXISTS asset_categories (
    id   SERIAL      PRIMARY KEY,
    name VARCHAR(60) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS assets (
    id              BIGSERIAL    PRIMARY KEY,
    asset_tag       VARCHAR(30)  NOT NULL UNIQUE,
    category_id     INTEGER      NOT NULL REFERENCES asset_categories(id),
    brand           VARCHAR(80),
    model           VARCHAR(80),
    serial_number   VARCHAR(100),
    purchase_date   DATE,
    purchase_cost   NUMERIC(10,2),
    warranty_expiry DATE,
    condition       VARCHAR(20)  NOT NULL DEFAULT 'GOOD',
    status          VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    location_id     UUID         REFERENCES locations(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- status:    AVAILABLE | ASSIGNED | IN_REPAIR | RETIRED | LOST
-- condition: GOOD | FAIR | DAMAGED | REPAIR

CREATE TABLE IF NOT EXISTS asset_assignments (
    id               BIGSERIAL   PRIMARY KEY,
    asset_id         BIGINT      NOT NULL REFERENCES assets(id),
    employee_user_id UUID        NOT NULL REFERENCES users(id),
    assigned_by      UUID        REFERENCES users(id),
    effective_from   TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to     TIMESTAMPTZ,
    acknowledged_at  TIMESTAMPTZ,
    return_condition VARCHAR(20),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Partial unique: only one open (current) assignment per asset at a time.
CREATE UNIQUE INDEX IF NOT EXISTS idx_asset_one_current_assignment
    ON asset_assignments(asset_id) WHERE effective_to IS NULL;
CREATE INDEX IF NOT EXISTS idx_asset_assignments_employee
    ON asset_assignments(employee_user_id);

CREATE TABLE IF NOT EXISTS asset_requests (
    id               BIGSERIAL   PRIMARY KEY,
    employee_user_id UUID        NOT NULL REFERENCES users(id),
    category_id      INTEGER     NOT NULL REFERENCES asset_categories(id),
    reason           TEXT        NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decided_by       UUID        REFERENCES users(id),
    decided_at       TIMESTAMPTZ,
    rejection_reason TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- status: PENDING | APPROVED | REJECTED | FULFILLED
CREATE INDEX IF NOT EXISTS idx_asset_requests_employee ON asset_requests(employee_user_id);
CREATE INDEX IF NOT EXISTS idx_asset_requests_status   ON asset_requests(status);

INSERT INTO asset_categories (name) VALUES
    ('Laptop'), ('Monitor'), ('Mobile Phone'), ('Access Card'),
    ('Peripheral'), ('Furniture')
ON CONFLICT (name) DO NOTHING;
