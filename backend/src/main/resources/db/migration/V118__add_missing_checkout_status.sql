-- NForce OneHR — Flyway Migration V118
-- Adds MISSING_CHECKOUT as a valid attendance_records.status value.
--
-- Backs AttendanceService.flagMissingCheckoutIfStale / WebClockInService.checkOut: a session
-- left open past its own workday/grace window (shiftDayCutover, e.g. 7:00 AM) is flagged this
-- way instead of being silently closed with a fabricated check-out time and computed worked
-- hours. checkOutAt and workedMinutes are deliberately left NULL — the real check-out time is
-- unknown, so none is guessed. Corrected via the existing Regularization flow, same as any
-- other attendance correction.

ALTER TABLE attendance_records DROP CONSTRAINT attendance_records_status_check;

ALTER TABLE attendance_records ADD CONSTRAINT attendance_records_status_check
    CHECK (status IN ('PRESENT', 'LATE', 'HALF_DAY', 'ABSENT', 'MISSING_CHECKOUT'));
