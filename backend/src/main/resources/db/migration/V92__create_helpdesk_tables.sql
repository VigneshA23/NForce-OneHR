-- HR Help Desk: lets employees raise HR support requests as trackable tickets instead of
-- emails. Three tables, following the master-data (document_types) and transactional-child
-- (onboarding_checklists/_items) precedents already in this schema:
--   helpdesk_categories - master data (topic dropdown), mirrors document_types exactly.
--   helpdesk_tickets    - one row per request, raised by an employee against a category.
--   helpdesk_replies    - the conversation thread on a ticket; is_internal marks HR-only notes
--                         that must never be exposed on the employee-facing endpoints.
-- Ticket numbers (HR-2026-000001) are generated from a dedicated sequence so they're gap-free
-- per year and never collide under concurrent submissions.

CREATE TABLE helpdesk_categories (
    id         SERIAL       PRIMARY KEY,
    name       VARCHAR(80)  NOT NULL UNIQUE,
    is_active  BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO helpdesk_categories (name) VALUES
    ('Attendance'),
    ('Leave'),
    ('Payroll'),
    ('Benefits'),
    ('Employee Documents'),
    ('Policies'),
    ('Profile'),
    ('Training'),
    ('Others');

CREATE SEQUENCE helpdesk_ticket_no_seq START 1;

CREATE TABLE helpdesk_tickets (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_number    VARCHAR(20)  NOT NULL UNIQUE,          -- HR-2026-000001
    employee_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id      INTEGER      NOT NULL REFERENCES helpdesk_categories(id),
    description      TEXT         NOT NULL,
    -- OPEN | ASSIGNED | IN_PROGRESS | WAITING_FOR_EMPLOYEE | RESOLVED | CLOSED
    status           VARCHAR(30)  NOT NULL DEFAULT 'OPEN',
    -- LOW | MEDIUM | HIGH | URGENT
    priority         VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',
    assigned_to      UUID REFERENCES users(id),
    resolved_at      TIMESTAMPTZ,
    resolved_by      UUID REFERENCES users(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_helpdesk_tickets_employee ON helpdesk_tickets(employee_user_id, created_at DESC);
CREATE INDEX idx_helpdesk_tickets_status   ON helpdesk_tickets(status);
CREATE INDEX idx_helpdesk_tickets_assignee ON helpdesk_tickets(assigned_to);

CREATE TABLE helpdesk_replies (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id        UUID         NOT NULL REFERENCES helpdesk_tickets(id) ON DELETE CASCADE,
    sender_id        UUID         NOT NULL REFERENCES users(id),
    -- EMPLOYEE | HR — who sent this reply, resolved server-side from the caller's role, never client-supplied
    sender_role      VARCHAR(20)  NOT NULL,
    message          TEXT         NOT NULL,
    is_internal      BOOLEAN      NOT NULL DEFAULT false,   -- HR-only note; stripped from employee-facing responses
    attachment_name  VARCHAR(255),
    attachment_type  VARCHAR(100),
    attachment_size  BIGINT,
    attachment_data  BYTEA,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_helpdesk_replies_ticket ON helpdesk_replies(ticket_id, created_at ASC);
