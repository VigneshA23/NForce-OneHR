package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skill_request")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SkillRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "requested_by_user_id", nullable = false)
    private UUID requestedByUserId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "note", length = 500)
    private String note;

    // Pending | Approved | Rejected | Mapped — validated at the request-DTO layer, not a DB
    // enum/check constraint, matching this codebase's existing convention for small fixed-choice
    // fields (see EmployeeSkill.proficiencyLevel, EmployeeLearningEntry.status).
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "Pending";

    @Column(name = "mapped_to_skill_id")
    private UUID mappedToSkillId;

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
