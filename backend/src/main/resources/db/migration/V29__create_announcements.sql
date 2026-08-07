CREATE TABLE announcements (
    id            BIGSERIAL    PRIMARY KEY,
    title         VARCHAR(200) NOT NULL,
    body          TEXT         NOT NULL,
    audience      VARCHAR(100) NOT NULL DEFAULT 'All Employees',
    scheduled_for TIMESTAMPTZ,
    published_at  TIMESTAMPTZ,
    created_by    UUID REFERENCES users(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
