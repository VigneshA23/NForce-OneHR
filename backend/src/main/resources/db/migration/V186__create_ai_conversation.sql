-- NForce OneHR — Flyway Migration V186
--
-- Short-term conversation memory for the AI assistant, so a follow-up like "what happens after
-- that?" can be answered in context.
--
-- Numbered 186 to follow V185 (the knowledge index). The shared dev Neon DB was still at V184 when
-- this was written, so 185 and 186 are both free; re-check max(version) before merging, since other
-- branches migrate against the same database.
--
-- Unlike ai_knowledge_chunk, these tables carry no vector columns and ARE mapped as ordinary JPA
-- entities. That is safe: Hibernate can create them under the H2 test profile without trouble.

CREATE TABLE ai_conversation (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Owner. Every read is filtered by this: a conversation id is a UUID, but guessing one must
    -- not be enough to read someone else's questions.
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_conversation_user ON ai_conversation (user_id, last_message_at DESC);

CREATE TABLE ai_conversation_message (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID        NOT NULL REFERENCES ai_conversation(id) ON DELETE CASCADE,

    -- USER or ASSISTANT. A plain VARCHAR with a CHECK, matching how help_content.status and
    -- helpdesk ticket status are already modelled here rather than a DB enum.
    sender          VARCHAR(16) NOT NULL,

    -- The question as asked, or the answer as returned. This is employee free text: it can contain
    -- personal circumstances ("I was in hospital on the 3rd"). It is never written to the
    -- application log, which runs at DEBUG in every environment, and it should be covered by a
    -- retention policy rather than kept indefinitely.
    content         TEXT        NOT NULL,

    -- HOW_TO | EXPLANATION | NAVIGATION | TROUBLESHOOTING | PERMISSION | UNKNOWN, for assistant
    -- turns only. Kept so retrieval quality can be reviewed without re-reading message content.
    response_type   VARCHAR(20),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_ai_message_sender CHECK (sender IN ('USER', 'ASSISTANT'))
);

-- Fetching the last N turns of one conversation, newest first.
CREATE INDEX idx_ai_message_conversation ON ai_conversation_message (conversation_id, created_at DESC);
