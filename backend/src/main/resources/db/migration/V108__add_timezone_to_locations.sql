-- Explicit per-location timezone (IANA id), so attendance (check-in/out, worked hours,
-- shift/date attribution, late/early calculations) can be computed in each employee's own
-- location's timezone instead of always the single global business zone
-- (app.attendance.zone). See AttendanceService.zoneIdFor.
--
-- Nullable and defaulted here rather than NOT NULL: a location with no timezone set falls
-- back to the global default at read time (AttendanceService.zoneIdFor), so this migration's
-- backfill is a best-effort guess from the free-text `country` column, not a hard requirement
-- — HR can correct any location afterward via Org Setup.
ALTER TABLE locations ADD COLUMN timezone VARCHAR(50);

UPDATE locations SET timezone = 'Asia/Kolkata' WHERE country ILIKE '%india%';
UPDATE locations SET timezone = 'Europe/London' WHERE country ILIKE '%united kingdom%' OR country ILIKE '%uk%';
UPDATE locations SET timezone = 'America/New_York' WHERE country ILIKE '%united states%' OR country ILIKE '%usa%';

-- Fallback: locations with no country set (or one we don't have explicit coverage for yet)
-- keep the current global default zone, so behavior is unchanged for every existing employee
-- until HR explicitly sets a location's timezone to something else.
UPDATE locations SET timezone = 'Asia/Kolkata' WHERE timezone IS NULL;
