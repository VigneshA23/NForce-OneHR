-- HR Help Desk: capture a 1-5 satisfaction rating from the employee once their ticket is CLOSED
-- (optional — the employee may never submit one). Feeds the HR-side analytics dashboard.
-- Reopening a ticket (HelpdeskService#reopenTicket) clears any rating already submitted, so a
-- ticket closed a second time can be rated again on its own merits.

ALTER TABLE helpdesk_tickets
    ADD COLUMN satisfaction_rating INTEGER,
    ADD COLUMN satisfaction_comment TEXT,
    ADD COLUMN satisfaction_submitted_at TIMESTAMPTZ;

ALTER TABLE helpdesk_tickets
    ADD CONSTRAINT chk_helpdesk_satisfaction_rating
        CHECK (satisfaction_rating IS NULL OR satisfaction_rating BETWEEN 1 AND 5);
