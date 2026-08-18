-- NForce OneHR — Flyway Migration V119
-- Records the IANA zone id (e.g. "Australia/Adelaide") the employee's browser/device reported
-- at Check-In / Web Clock-In, so that session's Check-Out, worked-minutes, and shift-day/
-- grace-window math all stay consistent even if the browser's reported zone later changes.
--
-- Nullable and left unbackfilled for existing rows — see AttendanceService.resolveZone, which
-- falls back to the employee's configured Location.timezone (then the global business zone)
-- whenever this is null.

ALTER TABLE attendance_records ADD COLUMN timezone VARCHAR(50);
