-- Records *why* the last token_version bump happened (see User.tokenVersion), so
-- JwtAuthenticationFilter can tell a password-change forced logout apart from a
-- profile/role-change one and the frontend can show the accurate message instead of a single
-- generic "password was changed" text for both. Nullable: rows bumped before this rollout, or
-- via any path that doesn't set it, simply fall back to a generic message on the frontend.
ALTER TABLE users ADD COLUMN token_version_reason VARCHAR(32);
