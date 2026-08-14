package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The result of one {@link com.nforce.onehr.service.AttendancePolicyEngine#evaluate} call.
 * {@code policyId}/{@code policyVersion} are null whenever {@code type} is
 * {@link PolicyDecisionType#NO_MATCH} — there is no policy to snapshot.
 *
 * <p>{@code deductionDays} is the configured leave-deduction amount for the specific rule that
 * matched (e.g. Late Arrival's {@code laDeductionDays}, or the specific Work Hours Shortage tier
 * that matched — not a version-level field) — set only when {@code type} is
 * {@code APPLY_PENALTY}, and copied verbatim onto {@link com.nforce.onehr.entity.AttendancePenalty#getDeductionDays()}
 * so the configured value has a real, persisted execution path rather than being stored-but-unused
 * configuration. This is a single per-occurrence amount, not an aggregation across multiple
 * shifts/incidents — every approved-screenshot example shows "deduct per 1 shift", so no
 * multi-shift batching rule is implemented (would be inventing a rule the screenshots never
 * demonstrate for a value other than 1).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PolicyDecision {

    private PolicyDecisionType type;
    private UUID policyId;
    private Integer policyVersion;
    private BigDecimal deductionDays;
    private String reason;
}
