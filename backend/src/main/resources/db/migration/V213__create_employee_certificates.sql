-- Standalone employee certificates — separate from employee_learning_entries' own
-- certificate_name/expiry fields (a cert earned outside any logged Learning entry, e.g. an
-- external certification, still needs a home). Same shape as employee_skills (see V211):
-- simple owned rows, no approval workflow, self-service only.
CREATE TABLE IF NOT EXISTS employee_certificates (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name               VARCHAR(255) NOT NULL,
    issuing_organization VARCHAR(255),
    credential_id      VARCHAR(255),
    credential_url     VARCHAR(500),
    issue_date         DATE,
    expiry_date        DATE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_employee_certificates_employee ON employee_certificates(employee_user_id);
