-- NForce OneHR — Flyway Migration V97
-- Holidays are scoped strictly by Location (see V34), but locationId was optional when
-- creating a user and no migration ever seeded holiday data — every holiday row that exists
-- today was added manually via the UI for one specific location, leaving every other
-- location's employees with an empty Holidays view. This migration:
--   1. Backfills a Location onto employees who were onboarded before locationId became
--      required (CreateUserRequest now enforces @NotNull going forward).
--   2. Seeds a generic starter holiday calendar for every location, so the module isn't
--      empty regardless of which location an employee belongs to. Dev/demo seed, same
--      spirit as V7's org master data — HR can edit the calendar via the UI afterward.
-- (Originally authored as V92/V93 — renumbered to V96/V97: the shared dev DB already had
-- an uncommitted Helpdesk migration occupying 92-95, applied directly and never checked in.)

UPDATE employees
SET location_id = (SELECT id FROM locations ORDER BY name LIMIT 1)
WHERE location_id IS NULL;

INSERT INTO holidays (holiday_name, holiday_date, location_id)
SELECT h.name, make_date(EXTRACT(YEAR FROM CURRENT_DATE)::INT, h.month, h.day), l.id
FROM locations l
CROSS JOIN (VALUES
    ('New Year''s Day', 1, 1),
    ('Republic Day', 1, 26),
    ('Independence Day', 8, 15),
    ('Gandhi Jayanti', 10, 2),
    ('Christmas', 12, 25)
) AS h(name, month, day)
ON CONFLICT (holiday_name, holiday_date, location_id) WHERE is_active = TRUE DO NOTHING;
