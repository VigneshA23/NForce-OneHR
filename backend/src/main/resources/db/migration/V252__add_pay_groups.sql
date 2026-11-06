-- NForce OneHR — Flyway Migration V252
-- US-C19 — Payroll Epic (ONEHR-579), Phase 1: Pay Group and pay schedule. No upstream dependency
-- on anything else in this epic; blocks US-C04, US-C07, US-C08.
CREATE TABLE pay_groups (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,
    legal_entity     VARCHAR(100),
    frequency        VARCHAR(20)  NOT NULL DEFAULT 'MONTHLY',
    currency         VARCHAR(3)   NOT NULL DEFAULT 'INR',
    cycle_start_day  INT          NOT NULL,
    cycle_end_day    INT          NOT NULL,
    processing_day   INT          NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_pay_groups_frequency CHECK (frequency IN ('MONTHLY')),
    CONSTRAINT chk_pay_groups_cycle_start_day CHECK (cycle_start_day BETWEEN 1 AND 31),
    CONSTRAINT chk_pay_groups_cycle_end_day CHECK (cycle_end_day BETWEEN 1 AND 31),
    CONSTRAINT chk_pay_groups_processing_day CHECK (processing_day BETWEEN 1 AND 31)
);

-- Seeded per US-C19's own acceptance criteria: "One 'Default pay group' seeded: cycle 26th to
-- 25th, monthly, INR." Processing day isn't pinned by that AC to an exact value — 26 (matching
-- the cycle's own start day) is a reasonable starting default, editable via the admin UI.
INSERT INTO pay_groups (name, frequency, currency, cycle_start_day, cycle_end_day, processing_day)
VALUES ('Default pay group', 'MONTHLY', 'INR', 26, 25, 26);
