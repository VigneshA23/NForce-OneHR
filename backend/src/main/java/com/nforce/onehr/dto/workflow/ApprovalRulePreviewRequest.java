package com.nforce.onehr.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Previews a NOT-YET-SAVED rule configuration — pure computation, never persists anything and
 * never touches whichever rule is actually active. {@code sampleValue} drives the worked example
 * ("Expense Amount: $750 → ... TRUE → Manager, HR"); when omitted it defaults to conditionValue
 * itself (the boundary case) so preview always has something concrete to show.
 */
@Data
public class ApprovalRulePreviewRequest {
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

    private BigDecimal sampleValue;
}
