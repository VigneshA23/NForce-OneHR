package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/** US-A03: one nightly point on the 90-day bench trend. */
@Entity
@Table(name = "bench_dashboard_snapshots")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BenchDashboardSnapshot {

    @Id
    @Column(name = "snapshot_date")
    private LocalDate snapshotDate;

    @Column(name = "benched_count", nullable = false)
    private int benchedCount;

    @Column(name = "total_headcount", nullable = false)
    private int totalHeadcount;
}
