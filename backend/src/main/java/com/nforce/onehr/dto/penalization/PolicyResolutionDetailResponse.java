package com.nforce.onehr.dto.penalization;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Section 21: "Employee X on date Y → which policy applies?" — the single authoritative answer,
 * built from the exact same {@code PenalizationPolicyResolutionService} the attendance engine and
 * every other screen reads from, never a second independently-derived lookup.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PolicyResolutionDetailResponse {

    private UUID employeeUserId;
    private LocalDate date;

    private UUID resolvedPolicyId;
    private String resolvedPolicyName;
    /** ALLOCATION / LEGACY / DEFAULT / ALLOCATION_REQUIRED. */
    private String resolvedPolicySource;
    /** ACTIVE / INACTIVE of the resolved policy; null when resolvedPolicyId is null. */
    private String policyStatus;

    private Integer policyVersion;
    private LocalDateTime versionEffectiveFrom;

    /** Present only when resolvedPolicySource == ALLOCATION. */
    private AllocationDto currentAllocation;

    /** Populated only when resolvedPolicyId is null — why nothing resolved. */
    private String reason;
}
