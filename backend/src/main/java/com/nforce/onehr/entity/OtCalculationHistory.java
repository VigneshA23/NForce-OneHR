package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ot_calculation_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OtCalculationHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "calculation_id", nullable = false)
    private UUID calculationId;

    @Column(nullable = false)
    private String event;

    @Column(name = "worked_minutes")
    private Integer workedMinutes;

    @Column(name = "ot_minutes")
    private Integer otMinutes;

    private BigDecimal multiplier;

    @Column(name = "hourly_rate")
    private BigDecimal hourlyRate;

    @Column(name = "ot_amount")
    private BigDecimal otAmount;

    private String status;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
}
