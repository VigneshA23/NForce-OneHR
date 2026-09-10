-- Adds additional overnight shift patterns alongside the existing "Regular Shift" (15:30-00:30),
-- so employees outside India (US/UK/other timezones) can be assigned a shift matching their own
-- local working hours instead of every employee sharing one shift definition. Purely additive —
-- no schema change; the existing Shift entity/repository and all shift-day/overnight-cutoff math
-- (AttendanceService/WebClockInService shiftDayOf, shiftEndCutoff) already handle any start/end
-- pair generically, so these rows need no code changes to work correctly.
INSERT INTO shifts (id, name, start_time, end_time, created_at)
VALUES
    (gen_random_uuid(), 'US Night Shift', '20:30', '05:30', now()),
    (gen_random_uuid(), 'UK Evening Shift', '18:30', '03:30', now()),
    (gen_random_uuid(), 'APAC Evening Shift', '17:30', '02:30', now())
ON CONFLICT (name) DO NOTHING;
