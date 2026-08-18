-- NForce OneHR — Flyway Migration V121
-- Bumped every time a user's role changes (see UserManagementService#updateUser). Lets an
-- already-issued JWT be invalidated without a server-side session store: the token carries its
-- token_version at issuance as the "tv" claim, and JwtAuthenticationFilter compares it against
-- this live column on every request — a mismatch (i.e. the role changed since the token was
-- issued) is treated the same as an unauthenticated request.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS token_version INT NOT NULL DEFAULT 0;
