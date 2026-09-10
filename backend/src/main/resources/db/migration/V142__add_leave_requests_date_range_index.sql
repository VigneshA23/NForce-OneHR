-- NForce OneHR — Flyway Migration V142
-- Performance only: adds an index to speed up the "who's on leave in [from,to]" queries
-- (LeaveService.listTeamLeave/listOrgLeave/listPeerLeave) that filter
-- "status = 'APPROVED' AND start_date <= :to AND end_date >= :from" — previously only
-- idx_leave_requests_status (V19) existed, so Postgres could narrow to APPROVED rows but
-- had to scan the date range without an index. No data is modified or removed.

CREATE INDEX IF NOT EXISTS idx_leave_requests_status_date_range
    ON leave_requests(status, start_date, end_date);
