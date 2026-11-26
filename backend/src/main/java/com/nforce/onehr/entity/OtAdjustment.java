package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ot_adjustments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OtAdjustment {
    public static final String PENDING = "PENDING";
    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "calculation_id", nullable = false)
    private UUID calculationId;

    @Column(name = "original_minutes", nullable = false)
    private int originalMinutes;

    @Column(name = "original_amount", nullable = false)
    private BigDecimal originalAmount;

    @Column(name = "new_minutes", nullable = false)
    private int newMinutes;

    @Column(name = "new_amount", nullable = false)
    private BigDecimal newAmount;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false)
    private String status;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_comment", columnDefinition = "TEXT")
    private String decisionComment;

    @PrePersist void onCreate() { if (requestedAt == null) requestedAt = LocalDateTime.now(); }
}
