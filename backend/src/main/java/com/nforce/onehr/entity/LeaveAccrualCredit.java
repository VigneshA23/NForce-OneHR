package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One month's leave credit for one employee and leave type (V314). The UNIQUE (employee, type, year,
 * month) key is what makes crediting safe to repeat: a credit is added to
 * {@link LeaveBalance#getTotalDays()} only when its row is inserted. MIGRATION rows (0 days) mark the
 * months of balances that existed before crediting did, so nothing is added on top of them.
 */
@Entity
@Table(name = "leave_accrual_credits")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveAccrualCredit {

    public static final String SOURCE_ACCRUAL = "ACCRUAL";
    public static final String SOURCE_MIGRATION = "MIGRATION";

    @Id
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    @Column(nullable = false)
    private Integer year;

    @Column(nullable = false)
    private Integer month;

    @Column(nullable = false)
    private BigDecimal days;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "credited_at", nullable = false)
    private LocalDateTime creditedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (creditedAt == null) creditedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
