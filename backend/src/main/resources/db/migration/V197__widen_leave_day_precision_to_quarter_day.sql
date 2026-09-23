-- NForce OneHR — Flyway Migration V197
-- Widens leave day-count columns from NUMERIC(5,1) to NUMERIC(5,2) so quarter-day amounts
-- (0.25) round-trip exactly instead of being silently rounded to the nearest 0.1 by Postgres.
--
-- PenaltyDeductionService.apply() adds an AttendancePenalty's deductionDays (NUMERIC(4,2) on
-- attendance_penalties — see V105/V104, e.g. a configured 0.25-day tier) straight onto
-- leave_balances.used_days. With used_days at NUMERIC(5,1), that 0.25 got rounded on write
-- (e.g. 15 - 0.25 stored as 14.8, not 14.75), which is what the dashboard/leave widgets were
-- displaying — the frontend renders the raw value verbatim, so the rounding was already baked
-- into the stored balance by the time it left the database.
--
-- leave_requests.total_days is widened alongside it for the same reason: it flows into
-- leave_balances.used_days on approval (LeaveService.approve), and NUMERIC(5,1) there would
-- reintroduce the identical precision loss the moment a fractional (sub-0.5) request is
-- approved.
ALTER TABLE leave_balances  ALTER COLUMN total_days TYPE NUMERIC(5,2);
ALTER TABLE leave_balances  ALTER COLUMN used_days  TYPE NUMERIC(5,2);
ALTER TABLE leave_requests  ALTER COLUMN total_days TYPE NUMERIC(5,2);
