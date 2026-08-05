-- NForce OneHR — Flyway Migration V44
-- (V42/V43 are already taken on the shared dev database by an out-of-band schema fix
-- with no corresponding committed migration file — see AttendanceService history.)
-- Policy change (supersedes the "strict one pair per day" note in V11): employees may
-- now check in/out multiple times in a single work day (e.g. a lunch break) instead of
-- exactly once. attendance_records stays one row per employee per day — check_in_at
-- keeps the day's FIRST check-in, check_out_at holds the LATEST check-out (or NULL while
-- a session is currently open), and worked_minutes accumulates across every session
-- instead of being derived from a single check-in/check-out pair.
--
-- session_started_at tracks when the currently-open session began, since check_in_at no
-- longer moves after the first punch of the day. It is only meaningful while
-- check_out_at is NULL and is otherwise ignored (regularization corrections never set it).

ALTER TABLE attendance_records ADD COLUMN session_started_at TIMESTAMPTZ;
