CREATE TABLE employee_documents (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_type_id INTEGER      NOT NULL REFERENCES document_types(id),
    file_name        VARCHAR(255) NOT NULL,
    file_url         VARCHAR(500) NOT NULL,
    file_data        BYTEA        NOT NULL,
    issue_date       DATE,
    expiry_date      DATE,
    status           VARCHAR(30)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    verified_by      UUID REFERENCES users(id),
    verified_at      TIMESTAMPTZ,
    rejection_reason TEXT,
    uploaded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE(employee_user_id, document_type_id)
);
