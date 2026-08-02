-- V25: Add level field to designations and holiday_region to locations.
-- level    → future compensation-band logic (L1–L5 or similar)
-- holiday_region → preparation for location-based holiday calendar (not built yet)

ALTER TABLE designations ADD COLUMN IF NOT EXISTS level VARCHAR(20);
ALTER TABLE locations    ADD COLUMN IF NOT EXISTS holiday_region VARCHAR(100);
