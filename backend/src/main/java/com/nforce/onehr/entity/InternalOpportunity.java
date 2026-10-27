package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** US-A10: a short-term internal need, visible to benched employees only while OPEN. */
@Entity
@Table(name = "internal_opportunities")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InternalOpportunity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "duration_weeks", nullable = false)
    private int durationWeeks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private OpportunityStatus status = OpportunityStatus.OPEN;

    @Column(name = "posted_by_user_id", nullable = false)
    private UUID postedByUserId;

    @Column(name = "assigned_employee_user_id")
    private UUID assignedEmployeeUserId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "opportunity_required_skills", joinColumns = @JoinColumn(name = "opportunity_id"))
    @Column(name = "skill_id")
    @Builder.Default
    private Set<UUID> requiredSkillIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "closed_at", columnDefinition = "TIMESTAMPTZ")
    private Instant closedAt;
}
