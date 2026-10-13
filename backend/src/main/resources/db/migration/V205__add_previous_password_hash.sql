-- ONEHR-561: password change/reset accepted a previously-used password because the only check
-- was against the current hash. Adds a single-slot history (the hash being replaced) so the
-- immediately-preceding password can also be rejected.
--
-- Guarded with IF NOT EXISTS: this is a shared dev database (see application.yml's
-- flyway.ignore-migration-patterns) that may already carry this column from an earlier,
-- since-removed migration file with a different version number.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS previous_password_hash VARCHAR(255);
