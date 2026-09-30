-- Birthday wishes: a teammate can send a short message to someone whose birthday is today.
-- Deliberately its own table rather than reusing `kudos` — Kudos' eligibility rule is relationship-
-- scoped (manager/peer/direct-report, or org-wide for HR/Super Admin only) and its `category` is a
-- fixed set of recognition chips, neither of which fits "anyone in the org may wish anyone else a
-- happy birthday on their actual birthday". Structurally identical to kudos otherwise: a one-way,
-- unapproved note that just creates a row and notifies the recipient.
CREATE TABLE birthday_wishes (
    id            BIGSERIAL PRIMARY KEY,
    from_user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    to_user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT birthday_wishes_not_self CHECK (from_user_id <> to_user_id)
);

CREATE INDEX idx_birthday_wishes_to_user_created ON birthday_wishes(to_user_id, created_at DESC);
