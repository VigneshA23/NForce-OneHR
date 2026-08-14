-- NForce OneHR — Flyway Migration V101
-- Shift window is 3:00 PM – 12:30 AM (end unchanged from V100, still an overnight shift —
-- consumers must handle end < start by wrapping past midnight, see AttendancePage.tsx).

UPDATE shifts SET start_time = '15:00', end_time = '00:30' WHERE name = 'Regular Shift';
