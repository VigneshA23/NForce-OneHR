-- NForce OneHR — Flyway Migration V201
-- Adds an independent "profile cover" image for the My Profile page header background, storing
-- it exactly like the existing profile_photo column (raw bytes, nullable, no default) so the
-- upload/remove pattern in ProfileService can mirror uploadPhoto/removePhoto exactly.
-- Nullable with no backfill: every existing employee simply has no custom cover yet and keeps
-- seeing the existing theme-color banner image, unchanged.
ALTER TABLE employees ADD COLUMN IF NOT EXISTS profile_cover BYTEA;
