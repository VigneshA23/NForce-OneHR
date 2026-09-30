package com.nforce.onehr.ai.repository;

import com.nforce.onehr.ai.entity.AiInteractionLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
