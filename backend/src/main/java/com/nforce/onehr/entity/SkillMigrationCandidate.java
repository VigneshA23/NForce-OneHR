package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skill_migration_candidate")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SkillMigrationCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "raw_text", nullable = false, length = 100)
    private String rawText;

    @Column(name = "normalized_text", nullable = false, length = 100)
    private String normalizedText;

    @Column(name = "occurrence_count", nullable = false)
    @Builder.Default
    private int occurrenceCount = 0;

    @Column(name = "proposed_skill_id")
    private UUID proposedSkillId;

    @Column(name = "resolved_skill_id")
    private UUID resolvedSkillId;

    // Pending | Mapped | NewSkillCreated | Discarded
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "Pending";

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
