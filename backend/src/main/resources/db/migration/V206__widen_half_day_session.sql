-- ONEHR-562 follow-up: half_day_session VARCHAR(10) fit 'FIRST_HALF' (10 chars) but not
-- 'SECOND_HALF' (11 chars), so submitting a second-half half-day leave request failed at the
-- DB layer with a generic error. Widen to fit both values with headroom.
ALTER TABLE leave_requests
    ALTER COLUMN half_day_session TYPE VARCHAR(20);
