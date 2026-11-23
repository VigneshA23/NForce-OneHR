package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One eligible occurrence of a PER_OCCURRENCE leave type (e.g. Maternity, V309). Opened by the first
 * request whose start date falls outside every earlier occurrence's window; every later request
 * starting inside [startDate, windowEndDate] draws from this same entitlement, so splitting leave
 * into several requests can never produce a fresh entitlement. Not a yearly balance: it never resets
 * in January and is never carried forward.
 */
@Entity
@Table(name = "leave_occurrences")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveOccurrence {

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

    @Column(name = "window_end_date", nullable = false)
    private LocalDate windowEndDate;

    @Column(name = "entitlement_days", nullable = false)
    private BigDecimal entitlementDays;

    @Column(name = "used_days", nullable = false)
    @Builder.Default
    private BigDecimal usedDays = BigDecimal.ZERO;

    @Version
    private Long version;

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
