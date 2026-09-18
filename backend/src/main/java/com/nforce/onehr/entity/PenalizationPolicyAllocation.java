package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One effective-dated row assigning an employee to a {@link PenalisationPolicy} — the
 * Organization Masters "Penalization Policy Allocation" screen's unit of work. Multiple rows can
 * exist per employee over time (CURRENT/FUTURE/HISTORICAL, derived from {@code effectiveFrom}/
 * {@code effectiveTo} against "today" — see the allocation service), the same
 * point-in-time-range contract {@link PenalizationPolicyVersion} already uses for policy
 * configuration itself and {@link EmployeeManagerHistory} uses for manager assignment.
 *
 * <p>Deliberately additive to {@link Employee#getPenalisationPolicy()} rather than replacing it —
 * that legacy FK remains the final fallback in {@code PenalizationPolicyResolutionService} for any
 * employee who has never been allocated through this table, so introducing this table changes no
 * existing employee's resolved policy.
 */
@Entity
@Table(name = "penalization_policy_allocations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PenalizationPolicyAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "penalisation_policy_id", nullable = false)
    private UUID penalisationPolicyId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
