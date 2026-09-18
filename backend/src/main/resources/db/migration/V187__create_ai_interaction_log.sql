-- NForce OneHR — Flyway Migration V187
--
-- One row per assistant turn: what was asked for, what came back, how well retrieval did, what it
-- cost and whether the user thought it was any good. This is the table that answers "why did the
-- assistant say that?" months later, when the conversation has been purged and nobody remembers.
--
-- Numbered 187 to follow V186 (conversations). The shared dev Neon DB was at V184 when this branch
-- was written, so 185–187 are free; re-check max(version) in flyway_schema_history before merging,
-- since other branches migrate against the same database and renumbering is routine here (see the
-- V95/V96/V102/V138 headers for the same note).
--
-- No vector columns, so this IS mapped as an ordinary JPA entity and Hibernate can create it under
-- the H2 test profile.

CREATE TABLE ai_interaction_log (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Nullable, and SET NULL rather than CASCADE on purpose: a turn refused before a conversation
    -- was created (rate limited, message too long, feature disabled) has no conversation, and
    -- purging old conversations under a retention policy must not also erase the quality metrics
    -- that have no personal data in them.
    conversation_id        UUID        REFERENCES ai_conversation(id) ON DELETE SET NULL,

    user_id                UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- The buckets retrieval actually filtered on, comma-joined, e.g. 'EMPLOYEE,MANAGER'. Recorded
    -- because a report of a wrong or missing answer is almost always really a question about which
    -- audience the asker was in at the time, and roles change.
    audience_buckets       VARCHAR(80) NOT NULL,

    -- The single role navigation was authorised against. Not derivable from audience_buckets: an
    -- HR Admin and a Super Admin both hold {EMPLOYEE, ...} and differ only here.
    shell_role             VARCHAR(20) NOT NULL,

    -- Where the user was when they asked, if they were anywhere the registry recognises.
    current_page_id        VARCHAR(60),

    -- HOW_TO | EXPLANATION | NAVIGATION | TROUBLESHOOTING | PERMISSION | UNKNOWN
    response_type          VARCHAR(20) NOT NULL,
    confidence             VARCHAR(10),

    -- The page finally offered, after NavigationValidator. Null when none was offered, and also
    -- null when the model proposed one that was then stripped as unreachable for this role — the
    -- two are not distinguished here, so a role-scoping bug shows up as absence rather than as a
    -- signal of its own.
    navigation_page_id     VARCHAR(60),

    -- The knowledge ids that went into the prompt, in rank order, as a JSON array. This is the
    -- single most useful column here: a wrong answer is traced by reading these back and looking
    -- at what the model was actually given, rather than guessing at the retrieval.
    retrieved_knowledge_ids JSONB      NOT NULL DEFAULT '[]'::jsonb,
    retrieved_count        INTEGER     NOT NULL DEFAULT 0,

    -- Best cosine score in the result set; NULL when nothing was retrieved. Note this is the best
    -- score that PASSED min-score, not the best score seen — the near-misses are discarded inside
    -- the retrieval SQL, so re-tuning the threshold downwards from this data is not possible yet.
    top_score              DOUBLE PRECISION,

    provider               VARCHAR(40),
    model                  VARCHAR(80),
    prompt_tokens          INTEGER,
    completion_tokens      INTEGER,
    latency_ms             INTEGER     NOT NULL DEFAULT 0,

    success                BOOLEAN     NOT NULL DEFAULT TRUE,

    -- Why a turn did not produce a real answer. See AiInteractionLogger for the authoritative
    -- list: DISABLED, EMPTY_MESSAGE, MESSAGE_TOO_LONG, RATE_LIMITED, NO_KNOWLEDGE,
    -- RETRIEVAL_UNAVAILABLE, PROVIDER_UNAVAILABLE, UNKNOWN_ANSWER. Deliberately not a CHECK
    -- constraint: a new code should not need a migration, and an unrecognised one here is a
    -- reporting oddity rather than corrupt data.
    error_code             VARCHAR(40),

    -- Length only. The question text itself lives in ai_conversation_message and is deliberately
    -- NOT duplicated here: it is employee free text that can describe personal circumstances, and
    -- copying it into a second table would double the retention surface for no analytical gain.
    -- The length is kept because "people ask very long questions and get UNKNOWN" is a real and
    -- actionable pattern that needs no personal data to see.
    question_chars         INTEGER     NOT NULL DEFAULT 0,

    -- UP or DOWN, set later by the feedback endpoint on the most recent turn of a conversation.
    feedback_rating        VARCHAR(10),
    -- Volunteered by the user specifically as feedback. Still free text: never written to the
    -- application log, which runs at DEBUG for com.nforce.onehr in every environment.
    feedback_comment       TEXT,
    feedback_at            TIMESTAMPTZ,

    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_ai_log_feedback CHECK (feedback_rating IS NULL OR feedback_rating IN ('UP', 'DOWN'))
);

-- The three ways this table actually gets read: recent activity, quality triage by outcome, and
-- "show me this user's history" when someone reports a problem.
CREATE INDEX idx_ai_log_created ON ai_interaction_log (created_at DESC);
CREATE INDEX idx_ai_log_type ON ai_interaction_log (response_type, created_at DESC);
CREATE INDEX idx_ai_log_user ON ai_interaction_log (user_id, created_at DESC);

-- Feedback writes look up the newest turn of one conversation.
CREATE INDEX idx_ai_log_conversation ON ai_interaction_log (conversation_id, created_at DESC);

-- RETENTION
-- Nothing purges this table automatically. It holds no message text, so it is far less sensitive
-- than ai_conversation_message, but it does link a user to a timestamped activity trail. When a
-- retention policy is agreed, the intended shape is: delete ai_conversation_message rows beyond
-- the window first, then ai_interaction_log rows beyond a longer one, keeping the aggregate
-- quality history after the personal detail has gone.
