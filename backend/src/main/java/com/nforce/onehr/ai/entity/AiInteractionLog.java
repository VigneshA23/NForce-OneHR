package com.nforce.onehr.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One assistant turn, recorded for quality and cost review.
 *
 * <p>Deliberately holds no message text — see {@link #questionChars} and the V187 migration. The
 * question and answer live in {@code ai_conversation_message}; this table is what remains useful
 * after those have been purged.
 */
@Entity
@Table(name = "ai_interaction_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiInteractionLog {

    public static final String FEEDBACK_UP = "UP";
    public static final String FEEDBACK_DOWN = "DOWN";

    @Id
    @GeneratedValue
    private UUID id;

    /** Null for turns refused before a conversation existed. */
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Comma-joined bucket names, in a stable order. */
    @Column(name = "audience_buckets", nullable = false, length = 80)
    private String audienceBuckets;

    @Column(name = "shell_role", nullable = false, length = 20)
    private String shellRole;

    @Column(name = "current_page_id", length = 60)
    private String currentPageId;

    @Column(name = "response_type", nullable = false, length = 20)
    private String responseType;

    @Column(length = 10)
    private String confidence;

    @Column(name = "navigation_page_id", length = 60)
    private String navigationPageId;

    /**
     * JSON array of the knowledge ids put in front of the model, in rank order.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} with <strong>no {@code columnDefinition}</strong>,
     * unlike {@code AuditLog}, which pins {@code "jsonb"}. A literal {@code jsonb} is unknown to
     * H2, so Hibernate's create-drop in the test profile fails that CREATE TABLE, warns, and
     * carries on — leaving the table absent. Letting the dialect choose gives {@code jsonb} on
     * Postgres and {@code json} on H2, so this maps on both. Flyway still owns the real type.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_knowledge_ids")
    private String retrievedKnowledgeIds;

    @Column(name = "retrieved_count", nullable = false)
    private int retrievedCount;

    @Column(name = "top_score")
    private Double topScore;

    @Column(length = 40)
    private String provider;

    @Column(length = 80)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_code", length = 40)
    private String errorCode;

    /** Length of the question, not the question. See the V187 migration comment. */
    @Column(name = "question_chars", nullable = false)
    private int questionChars;

    @Column(name = "feedback_rating", length = 10)
    private String feedbackRating;

    /** Never written to the application logger. */
    @Column(name = "feedback_comment", columnDefinition = "TEXT")
    private String feedbackComment;

    @Column(name = "feedback_at")
    private Instant feedbackAt;

    // No columnDefinition = "TIMESTAMPTZ", for the same reason as AiConversationMessage: H2 does
    // not know the type and the failure is a silent warning rather than an error.
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
