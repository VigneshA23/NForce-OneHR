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

import java.time.Instant;
import java.util.UUID;

/** One turn in a conversation, either the question asked or the answer returned. */
@Entity
@Table(name = "ai_conversation_message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConversationMessage {

    public static final String SENDER_USER = "USER";
    public static final String SENDER_ASSISTANT = "ASSISTANT";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /** USER or ASSISTANT. Plain String with a DB CHECK, matching help_content.status. */
    @Column(nullable = false, length = 16)
    private String sender;

    /** Employee free text. Never logged; see the V186 migration comment. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Assistant turns only. */
    @Column(name = "response_type", length = 20)
    private String responseType;

    // No columnDefinition = "TIMESTAMPTZ" here, deliberately, though 15 existing entities use it.
    // H2 does not know that type, so Hibernate's create-drop in the test profile fails the whole
    // CREATE TABLE - it only logs a warning and carries on, so those entities quietly have no
    // table under H2 and nothing noticed because no test queries them. These tables ARE queried
    // by tests, so the mapping has to work on both. Flyway still owns the real column type
    // (TIMESTAMPTZ, per V186); columnDefinition only ever affects generated DDL.
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
