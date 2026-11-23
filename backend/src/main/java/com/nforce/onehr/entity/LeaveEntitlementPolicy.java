package com.nforce.onehr.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entitlement days for one leave type from {@code effectiveYear} onwards (V309). The policy for a
 * year is the row with the greatest effectiveYear <= that year, so a change never rewrites balances
 * that already exist; it applies to balances created for its effective year and later.
 * Days per leave year for ANNUAL types, per occurrence for PER_OCCURRENCE types. Unpaid types have none.
 */
@Entity
@Table(name = "leave_entitlement_policies")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeaveEntitlementPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "leave_type_id", nullable = false)
    private UUID leaveTypeId;

    @Column(name = "effective_year", nullable = false)
    private Integer effectiveYear;

    @Column(nullable = false)
    private BigDecimal days;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
