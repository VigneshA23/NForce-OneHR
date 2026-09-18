-- Business Unit: a new Organization Master dimension, same shape as departments/designations/
-- locations (V3). employees.business_unit_id is nullable — existing employees are left
-- unassigned rather than mapped to a fabricated default; Organization Masters/Add User exposes
-- it as an optional field until an admin assigns one.

CREATE TABLE business_units (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       CITEXT      NOT NULL,
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT business_units_name_unique UNIQUE (name)
);

ALTER TABLE employees
    ADD COLUMN business_unit_id UUID REFERENCES business_units(id);

CREATE INDEX idx_employees_business_unit ON employees(business_unit_id);
