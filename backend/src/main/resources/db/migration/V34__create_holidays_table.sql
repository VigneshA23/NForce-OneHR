-- Holiday calendar: holidays scoped to a location, HR Admin/Super Admin managed.

CREATE TABLE holidays (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    holiday_name  VARCHAR(255) NOT NULL,
    holiday_date  DATE        NOT NULL,
    location_id   UUID        NOT NULL REFERENCES locations(id),
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_holiday_name_date_location UNIQUE (holiday_name, holiday_date, location_id)
);
