-- "Request new skill" flow (employee Skills tab): a skill missing from skill_master. HR/Super
-- Admin approve (creates/attaches a new skill_master row), reject, or map to an existing skill —
-- mapped_to_skill_id is only ever set on the Approved/Mapped outcome, null otherwise. status is a
-- plain VARCHAR validated at the request-DTO layer, matching this codebase's existing convention
-- for small fixed-choice fields (see employee_skills.proficiency_level, employee_learning_entries
-- .status) rather than a DB enum/check constraint.
CREATE TABLE IF NOT EXISTS skill_request (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_by_user_id UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                 VARCHAR(100) NOT NULL,
    category             VARCHAR(100),
    note                 VARCHAR(500),
    status               VARCHAR(20)  NOT NULL DEFAULT 'Pending',
    mapped_to_skill_id   UUID         REFERENCES skill_master(id),
    reviewed_by          UUID         REFERENCES users(id),
    reviewed_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_skill_request_requester ON skill_request(requested_by_user_id);
CREATE INDEX IF NOT EXISTS idx_skill_request_status ON skill_request(status);

-- One-time free-text-skill migration's staging/review table (Settings > Migration Review). One
-- row per distinct normalized value found across every employee_skills.skill_name at the time the
-- migration scan ran — never per employee-skill row, so 40 employees all typing "java" produce
-- exactly one candidate with occurrence_count=40, not 40 duplicate rows. proposed_skill_id is the
-- scan's own best-guess auto-match (nullable — "no confident match"); resolved_skill_id is what
-- HR actually decided (may differ from the proposal, or be a brand-new skill created on the
-- spot) and stays null until status leaves 'Pending'. Re-running the scan is idempotent: it only
-- inserts normalized values that don't already have a candidate row (ON CONFLICT DO NOTHING in
-- application code, not here — see the migration-scan service in a later phase), it never
-- resets a row HR already resolved.
CREATE TABLE IF NOT EXISTS skill_migration_candidate (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_text          VARCHAR(100) NOT NULL,
    normalized_text   VARCHAR(100) NOT NULL,
    occurrence_count  INTEGER      NOT NULL DEFAULT 0,
    proposed_skill_id UUID         REFERENCES skill_master(id),
    resolved_skill_id UUID         REFERENCES skill_master(id),
    status            VARCHAR(20)  NOT NULL DEFAULT 'Pending',
    reviewed_by        UUID        REFERENCES users(id),
    reviewed_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT skill_migration_candidate_normalized_unique UNIQUE (normalized_text)
);
CREATE INDEX IF NOT EXISTS idx_skill_migration_candidate_status ON skill_migration_candidate(status);
