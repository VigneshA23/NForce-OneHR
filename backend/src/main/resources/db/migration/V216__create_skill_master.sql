-- Org-wide Talent & Skills: the fixed skill master list that replaces free-text employee_skills
-- .skill_name entries (see V219 for the FK added onto that table, and the migration review
-- staging table in V220). CITEXT + unique name mirrors this codebase's existing master-list
-- convention (departments/designations/locations, see V3) — skill_master is the same kind of
-- org-wide lookup, just scoped to Talent instead of Org Setup.
CREATE TABLE IF NOT EXISTS skill_master (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       CITEXT      NOT NULL,
    category   VARCHAR(100),
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT skill_master_name_unique UNIQUE (name)
);

-- Alternate spellings/abbreviations ("Core Java", "JS") that Talent Search's autocomplete and the
-- one-time migration's auto-matching both resolve to a single skill_id — never searched/stored as
-- a separate concept from the skill itself. A merge (Settings > Skills) repoints every alias row
-- at the surviving skill_id rather than duplicating them.
CREATE TABLE IF NOT EXISTS skill_alias (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    skill_id         UUID        NOT NULL REFERENCES skill_master(id) ON DELETE CASCADE,
    alias            VARCHAR(100) NOT NULL,
    -- Trimmed/lowercased/punctuation-stripped form of `alias`, computed at write time (not a
    -- generated column — the normalization rule lives in application code, same reasoning as
    -- V220's skill_migration_candidate.normalized_text) so both use one shared implementation.
    normalized_alias VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One normalized alias can only ever resolve to one skill — otherwise "Core Java" matching
    -- both "Java" and some other skill would make auto-match/autocomplete ambiguous by design.
    CONSTRAINT skill_alias_normalized_unique UNIQUE (normalized_alias)
);
CREATE INDEX IF NOT EXISTS idx_skill_alias_skill ON skill_alias(skill_id);
