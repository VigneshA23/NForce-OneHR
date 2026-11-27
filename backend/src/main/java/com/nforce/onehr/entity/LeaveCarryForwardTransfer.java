package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Year-end ledger row (V310): what one employee's leave type carried from {@code fromYear} into the
 * next leave year. UNIQUE (employee, type, fromYear) is the duplicate-transfer guard; the job only
 * ever raises {@code carriedDays} (a pending request rejected after year-end frees days), never lowers it.
 * Since LV-05 a transfer is created by confirming the year-end wizard ({@code runId}); it also records the
 * days encashed, and unused = carried + encashed + lapsed.
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

    /** LV-05: days paid out at year-end (not carried, not lapsed). */
    @Column(name = "encashed_days", nullable = false)
    @Builder.Default
    private BigDecimal encashedDays = BigDecimal.ZERO;

    /** LV-05: the confirmed year-end run that created this transfer; null for the nightly job before LV-05. */
    @Column(name = "run_id")
    private UUID runId;

    /** LV-05: HR changed the figures in the wizard, so the transfer is never topped up afterwards. */
    @Column(name = "adjusted", nullable = false)
    @Builder.Default
    private boolean adjusted = false;

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
