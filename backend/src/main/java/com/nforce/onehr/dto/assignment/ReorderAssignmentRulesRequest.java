package com.nforce.onehr.dto.assignment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/** The domain's rule ids in the desired evaluation order (first = highest priority). */
@Getter @Setter
public class ReorderAssignmentRulesRequest {
    @NotBlank private String domain;
    @NotEmpty private List<UUID> orderedRuleIds;
}
