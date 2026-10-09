package com.nforce.onehr.ai;

import com.nforce.onehr.ai.entity.AiConversation;
import com.nforce.onehr.ai.entity.AiConversationMessage;
import com.nforce.onehr.ai.entity.AiInteractionLog;
import com.nforce.onehr.ai.repository.AiConversationMessageRepository;
import com.nforce.onehr.ai.repository.AiConversationRepository;
import com.nforce.onehr.ai.repository.AiInteractionLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the assistant's JPA entities actually map, against a real (H2) database.
 *
 * <p>This exists because of a trap this codebase has already fallen into once. Hibernate's
 * {@code create-drop} only <em>warns</em> when a CREATE TABLE fails — it logs and carries on — so
 * an entity with a column type H2 does not understand ends up with no table at all and a green
 * build. Fifteen existing OneHR entities pin {@code columnDefinition = "TIMESTAMPTZ"} and are in
 * exactly that state today; nobody noticed because no test queries them.
 *
 * <p>{@code contextLoads()} does not catch it, and neither does any Mockito test. Only writing a
 * row and reading it back does. Every assistant table that is mapped gets exercised here, including
 * the JSONB column, which is the other type H2 would have refused had it been pinned.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiSchemaMappingTest {

    @Autowired private AiConversationRepository conversations;
    @Autowired private AiConversationMessageRepository messages;
    @Autowired private AiInteractionLogRepository interactions;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("a conversation and its messages round-trip")
    @Transactional
    void conversationTablesExist() {
        UUID userId = UUID.randomUUID();
        AiConversation conversation = conversations.save(
                AiConversation.builder().userId(userId).build());

        messages.save(AiConversationMessage.builder()
                .conversationId(conversation.getId())
                .sender(AiConversationMessage.SENDER_USER)
                .content("how do I apply for leave?")
                .build());
        messages.save(AiConversationMessage.builder()
                .conversationId(conversation.getId())
                .sender(AiConversationMessage.SENDER_ASSISTANT)
                .content("Open Leave & Holidays, then Apply for Leave.")
                .responseType("HOW_TO")
                .build());

        assertThat(conversations.findByIdAndUserId(conversation.getId(), userId)).isPresent();
        assertThat(messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId())).hasSize(2);
        // The timestamp defaulting happens in @PrePersist rather than in the DDL default, so it has
        // to survive the round trip too.
        assertThat(messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).get(0).getCreatedAt())
                .isNotNull()
                .isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("an interaction log round-trips, JSON column included")
    @Transactional
    void interactionLogTableExistsAndHoldsJson() {
        UUID userId = UUID.randomUUID();
        AiConversation conversation = conversations.save(AiConversation.builder().userId(userId).build());

        interactions.save(AiInteractionLog.builder()
                .conversationId(conversation.getId())
                .userId(userId)
                .audienceBuckets("EMPLOYEE,MANAGER")
                .shellRole("MANAGER")
                .responseType("HOW_TO")
                .confidence("HIGH")
                .retrievedKnowledgeIds("[\"action.leave.apply\",\"workflow.leave.approval\"]")
                .retrievedCount(2)
                .topScore(0.91)
                .provider("mistral")
                .model("ministral-8b-latest")
                .latencyMs(842)
                .success(true)
                .questionChars(31)
                .build());

        List<AiInteractionLog> found = interactions.findByConversationIdAndUserIdOrderByCreatedAtDesc(
                conversation.getId(), userId, PageRequest.of(0, 1));

        assertThat(found).hasSize(1);
        // Reading the JSON column back is the assertion that matters: AuditLog pins
        // columnDefinition = "jsonb", which H2 rejects, so this entity deliberately lets the
        // dialect choose. If that ever gets "fixed" to match AuditLog, this fails.
        assertThat(found.get(0).getRetrievedKnowledgeIds()).contains("action.leave.apply");
        assertThat(found.get(0).getTopScore()).isEqualTo(0.91);
    }

    @Test
    @DisplayName("feedback writes back onto an existing row")
    @Transactional
    void feedbackColumnsAreWritable() {
        UUID userId = UUID.randomUUID();
        AiInteractionLog saved = interactions.save(AiInteractionLog.builder()
                .userId(userId).audienceBuckets("EMPLOYEE").shellRole("EMPLOYEE")
                .responseType("HOW_TO").retrievedKnowledgeIds("[]").build());

        saved.setFeedbackRating(AiInteractionLog.FEEDBACK_DOWN);
        saved.setFeedbackComment("it sent me to the wrong page");
        saved.setFeedbackAt(Instant.now());
        interactions.save(saved);

        assertThat(interactions.findById(saved.getId()))
                .get()
                .satisfies(entry -> {
                    assertThat(entry.getFeedbackRating()).isEqualTo("DOWN");
                    assertThat(entry.getFeedbackAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("the vector table is still not a JPA entity")
    void vectorTableIsNotMapped() {
        List<String> mappedToVectorTable = entityManager.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType)
                .filter(type -> {
                    Table table = type.getAnnotation(Table.class);
                    return table != null && table.name().startsWith("ai_knowledge_chunk");
                })
                .map(Class::getName)
                .toList();

        // Mapping ai_knowledge_chunk would make Hibernate emit vector(1024) against H2 and take
        // contextLoads() plus every other @SpringBootTest down with it. All vector I/O goes through
        // JdbcTemplate for this reason, and this is the check that keeps it that way.
        assertThat(mappedToVectorTable).isEmpty();
    }
}
