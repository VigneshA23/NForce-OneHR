-- NForce OneHR — Flyway Migration V125
-- V124 (Add First/Second Half and Custom day-type options to WFH requests) started writing
-- FULL_DAY/FIRST_HALF/SECOND_HALF into partial_day_mode for WFH rows (see
-- AttendanceRequestService.resolvePartialDayMode), but V107's CHECK constraint on that column
-- was never updated to allow those values — it still only permits Partial Day's three modes.
-- Every WFH submission therefore violates the constraint at commit time and fails with a
-- generic 500. This recreates the constraint to allow both request types' modes.
ALTER TABLE attendance_requests DROP CONSTRAINT attendance_requests_partial_day_mode_check;

ALTER TABLE attendance_requests
    ADD CONSTRAINT attendance_requests_partial_day_mode_check
        CHECK (partial_day_mode IN (
            'LATE_ARRIVE', 'INTERVENING_TIMEOFF', 'LEAVING_EARLY',
            'FULL_DAY', 'FIRST_HALF', 'SECOND_HALF'
        ));
