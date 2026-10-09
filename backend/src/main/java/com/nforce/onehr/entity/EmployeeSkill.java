package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "employee_skills")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeSkill {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "skill_name", nullable = false, length = 100)
    private String skillName;

    @Column(name = "proficiency_level", nullable = false, length = 20)
    private String proficiencyLevel;

    // Nullable, additive — a row is only considered "migrated" onto the Talent & Skills master
    // list once this (and proficiencyLevelId) is set; skillName/proficiencyLevel above are never
    // dropped and keep being the fallback for any row that hasn't been matched/confirmed yet. See
    // V219 and the skill_migration_candidate table (V220) for the one-time migration this backs.
    @Column(name = "skill_id")
    private UUID skillId;

    @Column(name = "proficiency_level_id")
    private UUID proficiencyLevelId;

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
