package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PN-04: one HR-defined auto-assignment rule. Generic across policy domains - see
 * {@code AssignmentRuleEngine}. All criteria that are set must match (AND); the lowest
 * {@code priority} number among matching active rules wins.
 */
@Entity
@Table(name = "assignment_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssignmentRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String domain;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private int priority;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "shift_id")
    private UUID shiftId;

    @Column(name = "employment_type", length = 30)
    private String employmentType;

    @Column(length = 100)
    private String grade;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
