package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Singleton overtime policy settings (row id 1). */
@Entity
@Table(name = "ot_settings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OtSettings {
    @Id
    private Short id;

    @Column(name = "min_ot_minutes", nullable = false)
    private int minOtMinutes;

    @Column(name = "rounding_minutes", nullable = false)
    private int roundingMinutes;

    @Column(name = "working_days_per_month", nullable = false)
    private BigDecimal workingDaysPerMonth;

    @Column(name = "require_approved_request", nullable = false)
    private boolean requireApprovedRequest;

    @Column(name = "max_request_minutes", nullable = false)
    private int maxRequestMinutes;

    @Column(name = "allow_duplicate_requests", nullable = false)
    private boolean allowDuplicateRequests;

    @Column(name = "adjustment_approval_required", nullable = false)
    private boolean adjustmentApprovalRequired;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
