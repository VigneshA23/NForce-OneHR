package com.nforce.onehr.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * The Super-Admin-editable AI Assistant rate-limit budget — a singleton row, same
 * UNIQUE/CHECK(singleton) enforcement as {@code attendance_rules} (see V162's comment for why, and
 * this entity's own migration V192). Ordinary JPA, no vector column, so it is unaffected by the
 * H2-vs-Hibernate constraint that keeps {@code ai_knowledge_chunk} off JPA entirely.
 */
@Entity
@Table(name = "ai_rate_limit_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRateLimitSettings {

    @Id
    @GeneratedValue
    private UUID id;

    // Always true — see the class Javadoc. Never toggled by application code; its only purpose is
    // the DB-level UNIQUE constraint that makes a second row impossible.
    @Column(nullable = false)
    @Builder.Default
    private boolean singleton = true;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "requests_per_window", nullable = false)
    private int requestsPerWindow;

    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = Instant.now();
    }
}
