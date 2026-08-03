package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policy_acknowledgments",
       uniqueConstraints = @UniqueConstraint(columnNames = {"policy_id", "employee_user_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PolicyAcknowledgment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "acknowledged_at", columnDefinition = "TIMESTAMPTZ")
    private Instant acknowledgedAt;
}
