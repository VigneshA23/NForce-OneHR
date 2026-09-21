package com.nforce.onehr.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Create/update payload for a Workflow Studio rule. Presence-only checks live here (jakarta
 * validation); everything semantic — is this requestType/conditionField/operator actually
 * supported, does conditionValue parse, are approvalStages valid role codes starting with
 * MANAGER, is this a duplicate — is deliberately NOT annotation-based, since those checks need
 * cross-field context and clear messages (see ApprovalRuleService#validate). Backend validation
 * is authoritative regardless of whatever the frontend already checked client-side.
 */
@Data
public class ApprovalRuleRequest {
    @NotBlank(message = "Rule name is required")
    @Size(max = 150, message = "Rule name must be 150 characters or fewer")
    private String ruleName;

    @NotBlank(message = "Request type is required")
    private String requestType;

    @NotBlank(message = "Condition field is required")
    private String conditionField;

    @NotBlank(message = "Operator is required")
    private String operator;

    @NotBlank(message = "Condition value is required")
    private String conditionValue;

    @NotEmpty(message = "At least one approval stage is required")
    private List<String> approvalStages;
}
