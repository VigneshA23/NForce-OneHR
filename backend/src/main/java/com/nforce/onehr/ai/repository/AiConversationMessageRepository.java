package com.nforce.onehr.ai.repository;

import com.nforce.onehr.ai.entity.AiConversationMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiConversationMessageRepository extends JpaRepository<AiConversationMessage, UUID> {

    /** Newest first, so a bounded history can be taken with a Pageable limit. */
    List<AiConversationMessage> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    /** Oldest first, for replaying a whole conversation to the UI. */
    List<AiConversationMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    void deleteByConversationId(UUID conversationId);
}
