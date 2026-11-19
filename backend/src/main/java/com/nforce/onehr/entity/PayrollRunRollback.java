package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per {@link PayrollRunService#rollbackWholeRun} call — unlike {@link PayrollRun}'s own
 * {@code lastRolledBackAt}/{@code lastRollbackReason}/{@code lastRolledBackBy} (which only ever
 * hold the most recent rollback), this is the full history: a run rolled back more than once
 * keeps every prior entry.
 */
@Entity
@Table(name = "payroll_run_rollbacks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayrollRunRollback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payroll_run_id", nullable = false)
    private UUID payrollRunId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "rolled_back_by")
    private UUID rolledBackBy;

    @Column(name = "rolled_back_at", nullable = false)
    private LocalDateTime rolledBackAt;

    @PrePersist
    protected void onCreate() {
        if (rolledBackAt == null) rolledBackAt = LocalDateTime.now();
    }
}
