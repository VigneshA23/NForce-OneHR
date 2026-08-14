-- NForce OneHR — Flyway Migration V96
-- Update the seeded default shift to the actual org shift window (an overnight shift —
-- end_time is on the following calendar day, same convention as V95's plain TIME columns;
-- consumers must handle end < start by wrapping past midnight, see AttendancePage.tsx).

UPDATE shifts SET start_time = '15:30', end_time = '00:30' WHERE name = 'Regular Shift';
