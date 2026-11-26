package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PN-04: the effective-dated record of what the rules assigned to one employee. Closed
 * ({@code effectiveTo}) and replaced when the outcome changes - history is never rewritten.
 * {@code ruleName} is a snapshot so the "why" survives a later rule rename or deletion.
 */
@Entity
@Table(name = "assignment_rule_assignments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssignmentRuleAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String domain;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "rule_id")
    private UUID ruleId;

    @Column(name = "rule_name", nullable = false, length = 150)
    private String ruleName;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }
}
