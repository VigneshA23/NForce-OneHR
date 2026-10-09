package com.nforce.onehr.ai.service;

import com.nforce.onehr.ai.config.AiProperties;
import com.nforce.onehr.ai.entity.AiConversation;
import com.nforce.onehr.ai.entity.AiConversationMessage;
import com.nforce.onehr.ai.prompt.PromptBuilder;
import com.nforce.onehr.ai.repository.AiConversationMessageRepository;
import com.nforce.onehr.ai.repository.AiConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Short-term conversational memory, so a follow-up such as "what happens after that?" has
 * something to refer back to.
 *
 * <p>Two properties are deliberate.
 *
 * <p><strong>Every read is scoped by owner.</strong> A conversation id is a UUID, but treating it
 * as unguessable would be the only thing protecting one employee's questions from another's. The
 * repository has no plain {@code findById} usage here.
 *
 * <p><strong>History is replayed as text and can never carry authority.</strong> Roles, audiences
 * and reachable pages are recomputed from the database on every request, so nothing an earlier turn
 * said - including anything a user talked the model into repeating - can establish a permission a
 * later turn relies on.
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final AiConversationRepository conversations;
    private final AiConversationMessageRepository messages;
    private final AiProperties properties;

    /**
     * Resolves the conversation this turn belongs to, creating one when needed.
     *
     * <p>An id that does not exist, or belongs to someone else, silently starts a fresh
     * conversation rather than failing. The user asked a question; refusing to answer it because a
     * client sent a stale id from a previous session would be a worse outcome than losing the
     * thread, and the two cases are indistinguishable from the caller's side anyway.
     */
    @Transactional
    public AiConversation resolve(String conversationId, UUID userId) {
        if (conversationId != null && !conversationId.isBlank()) {
            Optional<AiConversation> existing = parseUuid(conversationId)
                    .flatMap(id -> conversations.findByIdAndUserId(id, userId));
            if (existing.isPresent()) return existing.get();
        }
        return conversations.save(AiConversation.builder().userId(userId).build());
    }

    /** The last few exchanges, oldest first, for replay into the prompt. */
    @Transactional(readOnly = true)
    public List<PromptBuilder.ConversationTurn> recentTurns(UUID conversationId) {
        int turns = Math.max(0, properties.getLimits().getMaxHistoryTurns());
        if (turns == 0) return List.of();

        // Two rows per turn, newest first, then reversed - the alternative is loading a whole
        // conversation and discarding most of it, which grows without bound.
        List<AiConversationMessage> recent = messages.findByConversationIdOrderByCreatedAtDesc(
                conversationId, PageRequest.of(0, turns * 2));
        Collections.reverse(recent);

        List<PromptBuilder.ConversationTurn> result = new ArrayList<>();
        String pendingQuestion = null;
        for (AiConversationMessage message : recent) {
            if (AiConversationMessage.SENDER_USER.equals(message.getSender())) {
                pendingQuestion = message.getContent();
            } else if (pendingQuestion != null) {
                result.add(new PromptBuilder.ConversationTurn(pendingQuestion, message.getContent()));
                pendingQuestion = null;
            }
        }
        return result;
    }

    /** Records both halves of a completed turn. */
    @Transactional
    public void recordTurn(UUID conversationId, String question, String answer, String responseType) {
        messages.save(AiConversationMessage.builder()
                .conversationId(conversationId)
                .sender(AiConversationMessage.SENDER_USER)
                .content(question)
                .build());
        messages.save(AiConversationMessage.builder()
                .conversationId(conversationId)
                .sender(AiConversationMessage.SENDER_ASSISTANT)
                .content(answer)
                .responseType(responseType)
                .build());

        conversations.findById(conversationId).ifPresent(conversation -> {
            conversation.setLastMessageAt(Instant.now());
            conversations.save(conversation);
        });
    }

    /** Full transcript for the UI, owner-scoped. Empty when the caller does not own it. */
    @Transactional(readOnly = true)
    public List<AiConversationMessage> transcript(UUID conversationId, UUID userId) {
        return conversations.findByIdAndUserId(conversationId, userId)
                .map(conversation -> messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId()))
                .orElseGet(List::of);
    }

    /**
     * Clears a conversation's messages, keeping the conversation itself.
     *
     * <p>Deleting the row instead would invalidate the id the open browser tab is still holding,
     * so the next message would silently start a third conversation. Emptying it means "start
     * over" behaves the way the button implies.
     *
     * @return true if the caller owned it and it was cleared
     */
    @Transactional
    public boolean clear(UUID conversationId, UUID userId) {
        return conversations.findByIdAndUserId(conversationId, userId)
                .map(conversation -> {
                    messages.deleteByConversationId(conversation.getId());
                    return true;
                })
                .orElse(false);
    }

    private Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
