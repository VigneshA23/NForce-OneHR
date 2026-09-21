-- Decouples "this exception was detected" (attendance_exceptions row creation, which can happen
-- from either the dashboard-load path or the nightly scheduled job) from "the employee was
-- emailed about it" (notified_at). Email dispatch must happen exactly once, only from
-- PenaltyEvaluationScheduler's nightly run, never as a side effect of someone opening the
-- Exceptions dashboard — see ExceptionService#notifyUnnotifiedExceptions.
ALTER TABLE attendance_exceptions
    ADD COLUMN notified_at TIMESTAMP;
