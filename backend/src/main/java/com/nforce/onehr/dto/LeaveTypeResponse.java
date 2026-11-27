package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A leave type. The public listing ({@code GET /api/leave/types}) fills the first six fields; the
 * Leave Configuration view also fills the plan and usage fields. Quotas and rules live in leave plans (LV-01).
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
    /** Admin view only: the leave plans (any version) that include this type; empty = nobody can use it. */
    private java.util.List<String> planNames;
    /** Records referencing this type; above zero means it can only be deactivated, not deleted. */
    private Long referenceCount;
}
