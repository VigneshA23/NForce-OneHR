-- employee_certificates has never had a category — Talent & Skills' Settings screen introduces
-- one (Settings > Certificate Categories), V219 adds the nullable category_id column onto
-- employee_certificates itself. has_expiry is a per-category default only (e.g. "Internal
-- Training Completion" categories typically don't expire) — it does NOT gate whether an
-- individual certificate's own expiry_date can be set; that column stays freely nullable either way.
CREATE TABLE IF NOT EXISTS certificate_category (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       CITEXT      NOT NULL,
    has_expiry BOOLEAN     NOT NULL DEFAULT TRUE,
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT certificate_category_name_unique UNIQUE (name)
);
