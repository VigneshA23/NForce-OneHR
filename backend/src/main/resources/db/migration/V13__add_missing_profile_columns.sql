ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS phone                   VARCHAR(30),
    ADD COLUMN IF NOT EXISTS date_of_birth           DATE,
    ADD COLUMN IF NOT EXISTS gender                  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS personal_email          VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address                 TEXT,
    ADD COLUMN IF NOT EXISTS emergency_contact_name  VARCHAR(200),
    ADD COLUMN IF NOT EXISTS emergency_contact_phone VARCHAR(30),
    ADD COLUMN IF NOT EXISTS profile_photo           BYTEA;
