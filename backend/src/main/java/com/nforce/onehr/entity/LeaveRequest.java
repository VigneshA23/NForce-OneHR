package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "leave_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "is_half_day", nullable = false)
    @Builder.Default
    private boolean halfDay = false;

    @Column(name = "total_days", nullable = false)
    private BigDecimal totalDays;

    // Backfilled from is_half_day (see V140__add_leave_duration_type.sql); is_half_day remains
    // authoritative for every pre-existing consumer. Only ExpectedWorkHoursService reads this, to
    // know how much to shrink (rather than zero out) a day's expected work hours for approved
    // HOURLY/QUARTER_DAY leave — FULL_DAY/HALF_DAY submissions still zero the day out exactly as
    // before, via WorkingDayService, unrelated to this field.
    @Column(name = "duration_type", nullable = false, length = 20)
    @Builder.Default
    private String durationType = LeaveDurationType.FULL_DAY;

    // Hours requested for HOURLY leave only; null for every other duration type.
    @Column(name = "leave_hours")
    private BigDecimal leaveHours;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "employee_reason", nullable = false)
    private String employeeReason;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
