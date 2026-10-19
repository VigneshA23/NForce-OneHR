package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "opportunity_interests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OpportunityInterest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "opportunity_id", nullable = false)
    private UUID opportunityId;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "expressed_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant expressedAt = Instant.now();
}
