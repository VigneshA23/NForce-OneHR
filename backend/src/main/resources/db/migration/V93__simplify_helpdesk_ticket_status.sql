-- HR Help Desk: simplify the ticket status lifecycle from six ad-hoc states down to a strict,
-- linear four-state lifecycle — OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED — with no reopening
-- and no shortcut directly into CLOSED except from RESOLVED (see TicketStatus.java).
--
-- Data remap for existing rows:
--   ASSIGNED              -> OPEN         (assignment no longer implies status; the ticket had
--                                           an owner but work had not actually started yet)
--   WAITING_FOR_EMPLOYEE  -> IN_PROGRESS  (still an active, HR-engaged ticket, just paused on
--                                           the employee's input rather than newly raised)
-- OPEN, RESOLVED and CLOSED already map 1:1 onto the new set and are left untouched.

UPDATE helpdesk_tickets SET status = 'OPEN'        WHERE status = 'ASSIGNED';
UPDATE helpdesk_tickets SET status = 'IN_PROGRESS' WHERE status = 'WAITING_FOR_EMPLOYEE';

ALTER TABLE helpdesk_tickets
    ADD CONSTRAINT chk_helpdesk_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'));
