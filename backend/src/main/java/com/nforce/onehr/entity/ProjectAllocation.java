package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "project_allocations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProjectAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "capacity_percent", nullable = false)
    private Integer capacityPercent;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** Set when the allocation ends before its planned endDate; null = still running to plan. */
    @Column(name = "actual_end_date")
    private LocalDate actualEndDate;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
