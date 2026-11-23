package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "leave_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveType {

    /** Regular paid leave: an entitlement per leave year, kept in {@link LeaveBalance}. */
    public static final String BASIS_ANNUAL = "ANNUAL";
    /** e.g. Maternity: an entitlement per eligible occurrence, kept in {@link LeaveOccurrence} (V310). */
    public static final String BASIS_PER_OCCURRENCE = "PER_OCCURRENCE";

    public static final String CF_FIRST = "CARRY_FORWARD_FIRST";
    public static final String CURRENT_FIRST = "CURRENT_FIRST";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    // PAID or UNPAID (see LeaveTypeClassification). UNPAID types are unlimited: they never create,
    // read or deduct from any balance.
    @Column(nullable = false, length = 10)
    @Builder.Default
    private String classification = LeaveTypeClassification.PAID;

    /** Inactive types stay on historical requests but can't be chosen for new ones. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "entitlement_basis", nullable = false, length = 20)
    @Builder.Default
    private String entitlementBasis = BASIS_ANNUAL;

    /** PER_OCCURRENCE only: how long one occurrence stays open for further requests to draw from it. */
    @Column(name = "occurrence_window_months")
    private Integer occurrenceWindowMonths;

    @Column(name = "carry_forward_enabled", nullable = false)
    @Builder.Default
    private boolean carryForwardEnabled = false;

    /** Null = no cap (all unused days carry forward). */
    @Column(name = "carry_forward_max_days")
    private BigDecimal carryForwardMaxDays;

    /** Null = carried-forward days never expire; otherwise they expire N months into the new leave year. */
    @Column(name = "carry_forward_expiry_months")
    private Integer carryForwardExpiryMonths;

    @Column(name = "carry_forward_priority", nullable = false, length = 30)
    @Builder.Default
    private String carryForwardPriority = CF_FIRST;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean isPaid() {
        return LeaveTypeClassification.PAID.equals(classification);
    }

    /** Paid, yearly-balance leave: the only kind that has a {@link LeaveBalance} row. */
    public boolean isAnnualBalance() {
        return isPaid() && BASIS_ANNUAL.equals(entitlementBasis);
    }

    public boolean isPerOccurrence() {
        return isPaid() && BASIS_PER_OCCURRENCE.equals(entitlementBasis);
    }
}
