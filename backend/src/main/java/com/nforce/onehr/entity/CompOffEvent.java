package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Append-only audit trail of everything that happens to an employee's Comp-Off. */
@Entity
@Table(name = "comp_off_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompOffEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "credit_id")
    private UUID creditId;

    @Column(nullable = false, length = 30)
    private String event;

    private BigDecimal days;

    private String note;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
