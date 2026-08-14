-- "Appreciate your lead" / peer kudos (ONEHR-73). A lightweight one-way recognition note between
-- two employees — not tied to any review cycle, just a quick "thanks" surfaced from the My Team:
-- Peers view and its reporting-manager card.
CREATE TABLE kudos (
    id            BIGSERIAL PRIMARY KEY,
    from_user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    to_user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category      VARCHAR(30)  NOT NULL,
    note          TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT kudos_not_self CHECK (from_user_id <> to_user_id)
);

CREATE INDEX idx_kudos_to_user_created   ON kudos(to_user_id, created_at DESC);
CREATE INDEX idx_kudos_from_user_created ON kudos(from_user_id, created_at DESC);
