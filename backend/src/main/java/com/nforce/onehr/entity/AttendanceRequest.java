package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Work From Home / Partial Day self-declaration — see V93 migration for context. */
@Entity
@Table(name = "attendance_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    // Same resolution as RegularizationRequest: employee-selected approver (validated eligible)
    // else current manager via EmployeeManagerHistory, else NULL.
    @Column(name = "assigned_approver_id")
    private UUID assignedApproverId;

    @Column(name = "request_type", nullable = false, length = 20)
    private String requestType;

    @Column(name = "request_date", nullable = false)
    private LocalDate requestDate;

    @Column(name = "partial_day_hours")
    private BigDecimal partialDayHours;

    // PARTIAL_DAY: LATE_ARRIVE | INTERVENING_TIMEOFF | LEAVING_EARLY. WFH: FULL_DAY | FIRST_HALF
    // | SECOND_HALF — see AttendanceRequestService for how each mode is interpreted per type.
    @Column(name = "partial_day_mode", length = 30)
    private String partialDayMode;

    // WFH only: 1.00 (Full Day) or 0.50 (First Half / Second Half) — how much of this day counts
    // toward the monthly WFH day quota. Null for PARTIAL_DAY, which uses partialDayHours instead.
    @Column(name = "wfh_day_fraction")
    private BigDecimal wfhDayFraction;

    // A specific colleague to alert about this request — distinct from assignedApproverId, which
    // drives actual approval; purely informational.
    @Column(name = "notify_user_id")
    private UUID notifyUserId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

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
