package com.nforce.onehr.ai.repository;

import com.nforce.onehr.ai.entity.AiInteractionLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AiInteractionLogRepository extends JpaRepository<AiInteractionLog, UUID> {

    /**
     * The newest turn of one conversation, for attaching feedback.
     *
     * <p>Scoped by user id as well as conversation id for the same reason
     * {@code AiConversationRepository} has no unscoped finder: a conversation id is a UUID in a
     * request body, and rating someone else's answer must not be possible even though the damage
     * would be small. Ownership belongs in the query, not in a check the next caller might skip.
     */
    List<AiInteractionLog> findByConversationIdAndUserIdOrderByCreatedAtDesc(
            UUID conversationId, UUID userId, Pageable pageable);

    /**
     * Backs the Super-Admin "API Usage" dashboard (see AiUsageStatsService) — every turn in a
     * window, aggregated in Java rather than a GROUP BY query. Chatbot volume is nowhere near the
     * scale that would make in-memory bucketing a problem, and it keeps the date/error/response-type
     * bucketing logic in one place, portable across the real Postgres deployment and the H2 test
     * profile, instead of a native query only one of them could run.
     */
    List<AiInteractionLog> findByCreatedAtBetweenOrderByCreatedAtAsc(Instant from, Instant to);
}
