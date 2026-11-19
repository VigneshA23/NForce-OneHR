-- Keka restyle (payroll module) — "Filing Details" (§3.8): the statutory registration info a
-- pay group's legal entity actually files under, separate from the pay group's own operational
-- settings (cycle, currency, ...). Admin-entered only; nothing here is computed or validated
-- against an external filing system.

-- One row per pay group — Income Tax (PAN/TAN/Form 130 signatory) and the PF/ESI establishment
-- ids. A single flat table rather than three, since each of these is genuinely one value per pay
-- group (no history, no sub-list) — unlike Professional Tax below.
CREATE TABLE pay_group_filing_details (
    pay_group_id uuid PRIMARY KEY REFERENCES pay_groups(id),
    pan varchar(10),
    tan varchar(10),
    tan_circle_number varchar(50),
    cit_tds_location varchar(150),
    form130_signatory varchar(150),
    signatory_designation varchar(150),
    signatory_father_name varchar(150),
    pf_establishment_id varchar(50),
    esi_establishment_id varchar(50),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Professional Tax is registered per STATE, and within a state, per physical LOCATION (Keka's
-- "Registered PT Locations for <state>" cards) — a one-to-many a single flat table can't express,
-- so this is its own table. state_name is denormalized onto the row rather than a separate
-- "pt_registered_states" table, since the state list shown in the UI is just the distinct
-- state_name values already here — no state-level field exists that a location doesn't already
-- carry.
CREATE TABLE pt_registered_locations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    pay_group_id uuid NOT NULL REFERENCES pay_groups(id),
    state_name varchar(100) NOT NULL,
    location_name varchar(100) NOT NULL,
    establishment_id varchar(50),
    registration_date date,
    signatory varchar(150),
    signatory_designation varchar(150),
    signatory_father_name varchar(150),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_pt_registered_locations_pay_group ON pt_registered_locations(pay_group_id);
