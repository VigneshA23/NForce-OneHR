package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Year-end ledger row (V309): what one employee's leave type carried from {@code fromYear} into the
 * next leave year. UNIQUE (employee, type, fromYear) is the duplicate-transfer guard; the job only
 * ever raises {@code carriedDays} (a pending request rejected after year-end frees days), never lowers it.
 */
@Entity
@Table(name = "leave_carry_forward_transfers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveCarryForwardTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_user_id", nullable = false)
    private UUID employeeUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "from_year", nullable = false)
    private Integer fromYear;

    @Column(name = "unused_days", nullable = false)
    private BigDecimal unusedDays;

    @Column(name = "carried_days", nullable = false)
    private BigDecimal carriedDays;

    @Column(name = "lapsed_days", nullable = false)
    private BigDecimal lapsedDays;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private LocalDateTime processedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        processedAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
