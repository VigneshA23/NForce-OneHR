-- Adds hourly/quarter-day leave granularity to leave_requests, needed so approved short-duration
-- leave can reduce (rather than fully remove) a day's expected work hours in the Penalization
-- Policy engine and Attendance Summary. is_half_day is left untouched and stays authoritative for
-- every existing consumer — duration_type backfills to the equivalent value so old rows keep
-- calculating exactly as before.
--
-- Guarded with IF NOT EXISTS: this is a shared dev database (see application.yml's
-- flyway.ignore-migration-patterns) that may already carry these columns from an earlier,
-- since-removed migration file with a different version number — re-running this migration under
-- V140 must not fail if that already happened.

ALTER TABLE leave_requests
    ADD COLUMN IF NOT EXISTS duration_type VARCHAR(20) NOT NULL DEFAULT 'FULL_DAY';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_leave_requests_duration_type'
    ) THEN
        ALTER TABLE leave_requests
            ADD CONSTRAINT chk_leave_requests_duration_type
                CHECK (duration_type IN ('FULL_DAY', 'HALF_DAY', 'QUARTER_DAY', 'HOURLY'));
    END IF;
END $$;

UPDATE leave_requests SET duration_type = 'HALF_DAY' WHERE is_half_day = true AND duration_type = 'FULL_DAY';

-- Hours requested for HOURLY leave only; unused (null) for every other duration type.
ALTER TABLE leave_requests
    ADD COLUMN IF NOT EXISTS leave_hours NUMERIC(5, 2);
