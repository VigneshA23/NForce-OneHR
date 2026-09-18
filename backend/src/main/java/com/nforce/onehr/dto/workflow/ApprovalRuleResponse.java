package com.nforce.onehr.dto.workflow;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data @Builder
public class ApprovalRuleResponse {
    private UUID id;
    private String ruleName;
    private String requestType;
    private String conditionField;
    private String operator;
    private String conditionValue;
    private List<String> approvalStages;
    private boolean active;
    private String createdByName;
    private Instant createdAt;
    private String updatedByName;
    private Instant updatedAt;
}
