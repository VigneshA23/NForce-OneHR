package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LV-05: one year-end wizard run for a leave year. PREVIEW (lines computed, adjustable) → CONFIRMED (transfers,
 * next-year balances and encashments written) → ROLLED_BACK (all of it reversed, within the rollback window).
 * At most one PREVIEW and one CONFIRMED run per year.
 */
@Entity
@Table(name = "leave_year_end_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveYearEndRun {

    public static final String PREVIEW = "PREVIEW";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String ROLLED_BACK = "ROLLED_BACK";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "from_year", nullable = false)
    private Integer fromYear;

    @Column(nullable = false, length = 12)
    private String status;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "rolled_back_by")
    private UUID rolledBackBy;

    @Column(name = "rolled_back_at")
    private LocalDateTime rolledBackAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
