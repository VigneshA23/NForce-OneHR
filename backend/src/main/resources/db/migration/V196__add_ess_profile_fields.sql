-- ESS "My Profile" redesign: additive, nullable, no backfill needed.

-- 1. Employee self-service + job-tab fields.
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS first_name                    VARCHAR(100),
    ADD COLUMN IF NOT EXISTS middle_name                    VARCHAR(100),
    ADD COLUMN IF NOT EXISTS last_name                      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS preferred_name                 VARCHAR(100),
    ADD COLUMN IF NOT EXISTS bio                             TEXT,
    ADD COLUMN IF NOT EXISTS marital_status                 VARCHAR(30),
    ADD COLUMN IF NOT EXISTS emergency_contact_relationship  VARCHAR(50),
    ADD COLUMN IF NOT EXISTS permanent_address               TEXT,
    ADD COLUMN IF NOT EXISTS passport_number                VARCHAR(50),
    ADD COLUMN IF NOT EXISTS passport_expiry                DATE,
    ADD COLUMN IF NOT EXISTS bank_account_number             VARCHAR(50),
    ADD COLUMN IF NOT EXISTS bank_name                       VARCHAR(150),
    ADD COLUMN IF NOT EXISTS bank_ifsc                       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS national_id                     VARCHAR(50),
    ADD COLUMN IF NOT EXISTS job_code                        VARCHAR(50),
    ADD COLUMN IF NOT EXISTS probation_end_date              DATE,
    ADD COLUMN IF NOT EXISTS confirmation_date               DATE;

-- 2. Education history — one-to-many, direct self-edit, no approval workflow.
CREATE TABLE IF NOT EXISTS employee_education (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    institution_name  VARCHAR(255) NOT NULL,
    degree            VARCHAR(255) NOT NULL,
    field_of_study    VARCHAR(255),
    start_date        DATE,
    end_date          DATE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_employee_education_employee ON employee_education(employee_user_id);

-- 3. Asset requests — required-by date. WITHDRAWN needs no schema change: asset_requests.status
-- is a plain VARCHAR(20), not a DB enum/check constraint — the value is added purely at the
-- application layer (AssetService/AssetController).
ALTER TABLE asset_requests
    ADD COLUMN IF NOT EXISTS required_by_date DATE;
