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

/**
 * One assistant conversation. Ordinary JPA, unlike the knowledge index, because there is no vector
 * column here for Hibernate to choke on under the H2 test profile.
 */
@Entity
@Table(name = "ai_conversation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConversation {

    @Id
    @GeneratedValue
    private UUID id;

    /** Owner. Every lookup filters on this, so a guessed conversation id reveals nothing. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // No columnDefinition = "TIMESTAMPTZ" here, deliberately, though 15 existing entities use it.
    // H2 does not know that type, so Hibernate's create-drop in the test profile fails the whole
    // CREATE TABLE - it only logs a warning and carries on, so those entities quietly have no
    // table under H2 and nothing noticed because no test queries them. These tables ARE queried
    // by tests, so the mapping has to work on both. Flyway still owns the real column type
    // (TIMESTAMPTZ, per V186); columnDefinition only ever affects generated DDL.
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (lastMessageAt == null) lastMessageAt = now;
    }
}
