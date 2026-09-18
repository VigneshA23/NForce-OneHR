package com.nforce.onehr.dto.workflow;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data @Builder
public class ApprovalRulePreviewResponse {
    private String conditionField;
    private String operator;
    private String conditionValue;
    private BigDecimal sampleValue;
    /** e.g. "750.00 > 500.00" — display-only, mirrors the story's worked-example format. */
    private String conditionExpression;
    private boolean conditionResult;
    /** Routing that applies today, before this configuration is saved/activated. */
    private List<String> currentRouting;
    /** Routing this configuration would produce for sampleValue, if activated. */
    private List<String> newRouting;
}
