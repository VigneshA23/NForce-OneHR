package com.nforce.onehr.ai.repository;

import com.nforce.onehr.ai.entity.AiConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiConversationRepository extends JpaRepository<AiConversation, UUID> {

    /**
     * Ownership is part of the query, not a check afterwards. Finding the row and then comparing
     * user ids works until someone forgets the second step; this cannot be used incorrectly.
     */
    Optional<AiConversation> findByIdAndUserId(UUID id, UUID userId);
}
