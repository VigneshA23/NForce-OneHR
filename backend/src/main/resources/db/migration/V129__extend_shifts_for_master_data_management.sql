-- NForce OneHR — Flyway Migration V129
-- Extends the (previously code-only, Flyway-seeded) shifts table with the fields needed for a
-- real Shift master-data management screen (Super Admin only — see ShiftService/OrgController):
-- a human-facing code, a free-text description, a fixed/flexible flag, an optional per-shift
-- break duration, and an optional working-days override (same comma-separated DayOfWeek-name
-- convention as weekly_off_policies.off_days, for consistency — see WeeklyOffPolicy). All
-- nullable/defaulted so the existing seeded rows (Regular Shift, US/UK/APAC Night/Evening
-- Shifts) remain valid with no backfill required.
--
-- "Different timings per day" (a shift whose start/end vary by day of week) is NOT covered by
-- this migration -- that needs a separate normalized per-weekday table, a materially bigger
-- schema change than fits this pass. Deliberately out of scope here; flagged separately.
ALTER TABLE shifts ADD COLUMN IF NOT EXISTS code VARCHAR(30) UNIQUE;
ALTER TABLE shifts ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE shifts ADD COLUMN IF NOT EXISTS flexible BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shifts ADD COLUMN IF NOT EXISTS break_minutes INTEGER;
ALTER TABLE shifts ADD COLUMN IF NOT EXISTS working_days VARCHAR(60);
ALTER TABLE shifts ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
