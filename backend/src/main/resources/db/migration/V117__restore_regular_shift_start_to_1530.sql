-- NForce OneHR — Flyway Migration V117
-- Data fix: confirm "Regular Shift" is 3:30 PM – 12:30 AM, matching ShiftSeedCorrector.
--
-- V100 set 15:30. V101 changed it to 15:00. ShiftSeedCorrector (added in 02bd534 to match
-- V101's 15:00) was itself changed to 15:30 in d025ea0, with no matching migration — leaving
-- the corrector fighting V101's 15:00 on every app restart. 15:30 is confirmed correct;
-- this migration brings the DB in line with the corrector so they stop disagreeing.

UPDATE shifts SET start_time = '15:30', end_time = '00:30' WHERE name = 'Regular Shift';
