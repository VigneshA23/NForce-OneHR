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
    /** LV-02, {@code /types/mine} only: the caller's plan rules for this type, shown on the apply form; null = no limits. */
    private LeaveRuleSummary rules;
    /** LV-06, {@code /types/mine} only: held back today by tenure or probation — the first day it can be taken (null = unknown). */
    private java.time.LocalDate availableFrom;
    /** LV-06: why it is held back, e.g. "Paid Leave isn't available during probation: you can take it from 1 Mar 2027." */
    private String availabilityNote;
}
