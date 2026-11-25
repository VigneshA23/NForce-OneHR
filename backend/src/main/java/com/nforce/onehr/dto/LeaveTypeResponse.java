package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A leave type. The public listing ({@code GET /api/leave/types}) fills the first six fields; the
 * Leave Configuration view also fills the entitlement / carry-forward / usage fields.
 */
@Data @Builder
public class LeaveTypeResponse {
    private UUID id;
    private String code;
    private String name;
    private String classification;
    private String entitlementBasis;
    private boolean active;
    /** PAID / UNPAID for the two built-in types (can't be deleted, deactivated or reclassified); null otherwise. */
    private String systemKey;

    private Integer occurrenceWindowMonths;
    /** Policy days in force this leave year; null for unpaid (unlimited). */
    private BigDecimal currentEntitlementDays;
    /** YEARLY or MONTHLY crediting in force this leave year (annual paid types only). */
    private String currentCreditFrequency;
    /** Admin view only: who this type applies to. */
    private LeaveConfigDtos.EligibilityConfig eligibility;
    private boolean carryForwardEnabled;
    private BigDecimal carryForwardMaxDays;
    private Integer carryForwardExpiryMonths;
    private String carryForwardPriority;
    /** Records referencing this type; above zero means it can only be deactivated, not deleted. */
    private Long referenceCount;
}
