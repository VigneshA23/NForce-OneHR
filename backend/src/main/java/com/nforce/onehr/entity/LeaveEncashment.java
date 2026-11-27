package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LV-05: a confirmed year-end encashment on its way to payroll. PENDING until a payroll draft is generated for
 * the employee, which adds it as a "Leave Encashment" ad-hoc payment (ADDED, with the run and payment ids);
 * CANCELLED by a year-end rollback.
 */
@Entity
@Table(name = "leave_encashments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveEncashment {

    public static final String PENDING = "PENDING";
    public static final String ADDED = "ADDED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    @Column(name = "from_year", nullable = false)
    private Integer fromYear;

    @Column(nullable = false)
    private BigDecimal days;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(name = "payroll_run_id")
    private UUID payrollRunId;

    @Column(name = "ad_hoc_payment_id")
    private UUID adHocPaymentId;

    @Column(name = "created_by")
    private UUID createdBy;

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
