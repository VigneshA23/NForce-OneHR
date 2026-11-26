package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PN-03: record that one early warning was sent - the de-duplication guarantee. The unique key
 * (employee, type, period start, threshold) means the same threshold can fire at most once per
 * period; a new period (next month / week) has a new period_start and so can fire again.
 */
@Entity
@Table(name = "penalty_early_warnings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PenaltyEarlyWarning {

    public static final String LATE_ARRIVAL = "LATE_ARRIVAL";
    public static final String MISSING_LOGS = "MISSING_LOGS";
    public static final String WORK_HOURS_SHORTAGE = "WORK_HOURS_SHORTAGE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "warning_type", nullable = false, length = 30)
    private String warningType;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "threshold_key", nullable = false, length = 40)
    private String thresholdKey;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @PrePersist
    protected void onCreate() {
        if (sentAt == null) sentAt = LocalDateTime.now();
    }
}
