-- Org master tables: departments, designations, locations
-- All names use CITEXT for case-insensitive uniqueness enforcement.

CREATE TABLE departments (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       CITEXT      NOT NULL,
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT departments_name_unique UNIQUE (name)
);

CREATE TABLE designations (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    title      CITEXT      NOT NULL,
    grade      VARCHAR(50),
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT designations_title_unique UNIQUE (title)
);

CREATE TABLE locations (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       CITEXT       NOT NULL,
    city       VARCHAR(100),
    state      VARCHAR(100),
    country    VARCHAR(100),
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT locations_name_unique UNIQUE (name)
);
