-- Replace the plain UNIQUE constraint on users.email with a partial unique
-- index that only covers non-deleted rows. Soft-deleted accounts keep their
-- row (for history) but their email is freed for reuse on a new account.
-- Two active (deleted_at IS NULL) accounts with the same email still blocked.

ALTER TABLE users DROP CONSTRAINT users_email_key;

CREATE UNIQUE INDEX users_email_unique_active
    ON users (email)
    WHERE deleted_at IS NULL;
