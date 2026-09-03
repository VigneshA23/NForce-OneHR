package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "leave_balances")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(nullable = false)
    private Integer year;

    @Column(name = "total_days", nullable = false)
    private BigDecimal totalDays;

    @Column(name = "used_days", nullable = false)
    @Builder.Default
    private BigDecimal usedDays = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Section 6: this row is read-modify-written by up to four independent services (LeaveService's
    // submit reservation and approve, PenaltyDeductionService's PAID_LEAVE debit, and
    // AttendancePenaltyService's reversal credit) with no other coordination between them.
    // Optimistic locking is the minimal fix that actually matters here — without it, two
    // concurrent writers (e.g. a leave approval racing a penalty evaluation for the same employee)
    // can each read the same usedDays, and the second save silently overwrites the first's change
    // instead of failing loudly. @Version turns that into ObjectOptimisticLockingFailureException
    // (see GlobalExceptionHandler) rather than a silently wrong balance.
    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
