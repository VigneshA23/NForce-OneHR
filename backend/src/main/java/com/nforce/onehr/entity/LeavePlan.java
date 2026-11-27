package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LV-01: a Leave Plan bundles leave types with their own rules ({@link LeavePlanVersion},
 * {@link LeavePlanTypeRule}) and is assigned to employees ({@link LeavePlanAllocation}, assignment
 * rules, or the org default). Every employee has exactly one plan on any date.
 */
@Entity
@Table(name = "leave_plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeavePlan {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    /** 1 = January. Only January is allowed for now (DB check); stored so plans are ready for other months. */
    @Column(name = "leave_year_start_month", nullable = false)
    @Builder.Default
    private Integer leaveYearStartMonth = 1;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = ACTIVE;

    @Column(name = "is_org_default", nullable = false)
    @Builder.Default
    private boolean orgDefault = false;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
