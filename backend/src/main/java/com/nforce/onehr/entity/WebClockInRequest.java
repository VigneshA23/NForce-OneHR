package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "web_clock_in_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WebClockInRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    // Resolved once at submission time, same as RegularizationRequest: the employee's
    // current manager via EmployeeManagerHistory, else NULL (HR/Super Admin have blanket
    // override visibility regardless — see WebClockInService.listPendingForApprover).
    @Column(name = "assigned_approver_id")
    private UUID assignedApproverId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "requested_check_in", nullable = false)
    private LocalDateTime requestedCheckIn;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING";

    // Set via the no-approval-needed "Web Clock Out" action, only once approved.
    @Column(name = "checked_out_at")
    private LocalDateTime checkedOutAt;

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
