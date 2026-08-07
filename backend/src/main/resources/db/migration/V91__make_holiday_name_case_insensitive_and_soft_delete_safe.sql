-- holiday_name was a plain (case-sensitive) VARCHAR, letting "Independence day"
-- and "independence day" exist as two separate holidays for the same
-- date/location. Convert to CITEXT, matching this project's convention for
-- other name columns (departments.name, designations.title, locations.name).
--
-- Separately, the composite UNIQUE constraint applied table-wide, including
-- inactive rows — the same soft-delete conflict V10 solved for users.email.
-- Replace it with a partial unique index scoped to active rows only.

-- Deactivate case-insensitive duplicate holidays that are currently both
-- active, keeping the earliest-created row of each duplicate group, so the
-- unique index below doesn't fail against existing data.
UPDATE holidays
SET is_active = FALSE
WHERE is_active = TRUE
  AND id NOT IN (
      SELECT DISTINCT ON (lower(holiday_name), holiday_date, location_id) id
      FROM holidays
      WHERE is_active = TRUE
      ORDER BY lower(holiday_name), holiday_date, location_id, created_at
  );

ALTER TABLE holidays DROP CONSTRAINT IF EXISTS uk_holiday_name_date_location;

ALTER TABLE holidays ALTER COLUMN holiday_name TYPE CITEXT;

CREATE UNIQUE INDEX uk_holiday_name_date_location_active
    ON holidays (holiday_name, holiday_date, location_id)
    WHERE is_active = TRUE;
