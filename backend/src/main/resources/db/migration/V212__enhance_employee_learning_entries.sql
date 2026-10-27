-- Growth tab enhancement: "What I Learned" becomes "Learning & Development" — a learning entry
-- now carries a type/status/provider/dates/skills/certificate/verification instead of just a
-- free-text title + single date. entry_date (completion date) becomes nullable because a
-- Planned or In Progress entry has no completion date yet; existing rows are all implicitly
-- completed, so their entry_date is left as-is and status defaults to 'Completed'.
--
-- IF NOT EXISTS on every ADD COLUMN: this migration previously ran against this shared dev
-- database under an earlier version number (renumbered to resolve a collision with another
-- branch's own V206/V208 — see this repo's flyway.ignore-migration-patterns comment in
-- application.yml for why a branch's migrations can run out of sequence against this DB).
-- Without it, Flyway re-running this under its new number would fail on "column already exists".
ALTER TABLE employee_learning_entries
    ALTER COLUMN entry_date DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS learning_type          VARCHAR(30)  NOT NULL DEFAULT 'Other',
    ADD COLUMN IF NOT EXISTS status                 VARCHAR(20)  NOT NULL DEFAULT 'Completed',
    ADD COLUMN IF NOT EXISTS provider               VARCHAR(150),
    ADD COLUMN IF NOT EXISTS start_date              DATE,
    -- Comma-separated skill names picked from the employee's own employee_skills list — same
    -- plain-CSV-column convention as expense_claims.approval_stages (see ApprovalRuleEvaluationService
    -- .toCsv), not a join table: lightweight tags, not a referential integrity requirement.
    ADD COLUMN IF NOT EXISTS skills_developed        VARCHAR(500),
    ADD COLUMN IF NOT EXISTS certificate_name        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS certificate_issue_date  DATE,
    ADD COLUMN IF NOT EXISTS certificate_expiry_date DATE,
    ADD COLUMN IF NOT EXISTS certificate_url         VARCHAR(500),
    -- Self Reported | Pending Verification | Verified — always starts Self Reported; nothing
    -- employee-facing can move it to Verified (that's the manager/HR phase, not built yet).
    ADD COLUMN IF NOT EXISTS verification_status     VARCHAR(25)  NOT NULL DEFAULT 'Self Reported',
    ADD COLUMN IF NOT EXISTS notes                   TEXT;

CREATE INDEX IF NOT EXISTS idx_employee_learning_entries_status ON employee_learning_entries(status);
