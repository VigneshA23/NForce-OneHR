package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "comp_off_utilizations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompOffUtilization {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "credit_id", nullable = false)
    private UUID creditId;

    @Column(name = "leave_request_id", nullable = false)
    private UUID leaveRequestId;

    @Column(nullable = false)
    private BigDecimal days;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
