package com.nforce.onehr.dto.workflow;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * What the rule builder form is allowed to offer right now — backed entirely by
 * ApprovalRuleService's SUPPORTED_* constants, so adding Leave/Regularization/Asset support in a
 * later phase is a backend constant change picked up automatically here, never a frontend
 * redeploy just to unlock a new dropdown option.
 */
@Data @Builder
public class ApprovalRuleMetadataResponse {
    private List<String> requestTypes;
    /** Condition fields allowed per request type, e.g. {"EXPENSE": ["AMOUNT"]}. */
    private Map<String, List<String>> conditionFieldsByRequestType;
    private List<String> operators;
    private List<String> approvalRoles;
}
