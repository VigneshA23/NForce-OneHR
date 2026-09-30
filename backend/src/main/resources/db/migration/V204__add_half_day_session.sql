-- ONEHR-562: half-day leave requests had no way to record which half of the day was taken.
-- Nullable — only meaningful when is_half_day = true; full-day/quarter-day/hourly rows leave it null.
--
-- Guarded with IF NOT EXISTS: this is a shared dev database (see application.yml's
-- flyway.ignore-migration-patterns) that may already carry this column from an earlier,
-- since-removed migration file with a different version number.

ALTER TABLE leave_requests
    ADD COLUMN IF NOT EXISTS half_day_session VARCHAR(10);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_leave_requests_half_day_session'
    ) THEN
        ALTER TABLE leave_requests
            ADD CONSTRAINT chk_leave_requests_half_day_session
                CHECK (half_day_session IN ('FIRST_HALF', 'SECOND_HALF'));
    END IF;
END $$;
