-- How much of this WFH day counts toward the monthly WFH day quota: 1.00 (Full Day) or 0.50
-- (First Half / Second Half — see partial_day_mode, whose allowed values now also include
-- FULL_DAY/FIRST_HALF/SECOND_HALF for WFH rows). Null for PARTIAL_DAY rows, which use
-- partial_day_hours instead.
ALTER TABLE attendance_requests ADD COLUMN wfh_day_fraction NUMERIC(3,2);
