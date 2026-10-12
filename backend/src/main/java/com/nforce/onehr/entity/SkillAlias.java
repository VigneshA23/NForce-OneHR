package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skill_alias")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SkillAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "alias", nullable = false, length = 100)
    private String alias;

    @Column(name = "normalized_alias", nullable = false, length = 100)
    private String normalizedAlias;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
