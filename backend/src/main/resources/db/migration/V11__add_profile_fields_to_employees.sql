ALTER TABLE employees
    ADD COLUMN phone                   VARCHAR(30),
    ADD COLUMN date_of_birth           DATE,
    ADD COLUMN gender                  VARCHAR(20),
    ADD COLUMN personal_email          VARCHAR(255),
    ADD COLUMN address                 TEXT,
    ADD COLUMN emergency_contact_name  VARCHAR(200),
    ADD COLUMN emergency_contact_phone VARCHAR(30),
    ADD COLUMN profile_photo           BYTEA;
