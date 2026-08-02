CREATE TABLE document_types (
    id                            SERIAL PRIMARY KEY,
    name                          VARCHAR(80)  NOT NULL UNIQUE,
    requires_verification         BOOLEAN      NOT NULL DEFAULT TRUE,
    requires_expiry_date          BOOLEAN      NOT NULL DEFAULT FALSE,
    applicable_employment_types   VARCHAR(200),
    applicable_locations          VARCHAR(200),
    is_active                     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO document_types (name, requires_verification, requires_expiry_date, applicable_employment_types, applicable_locations) VALUES
    ('Work Authorization',      true,  true,  NULL, NULL),
    ('Identity Document',       true,  true,  NULL, NULL),
    ('Employment Contract',     true,  false, NULL, NULL),
    ('Educational Certificate', true,  false, NULL, NULL),
    ('Personal Document',       false, false, NULL, NULL);
