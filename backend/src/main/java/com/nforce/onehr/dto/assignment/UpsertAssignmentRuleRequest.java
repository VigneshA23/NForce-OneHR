package com.nforce.onehr.dto.assignment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** Create or update one assignment rule. At least one criterion is required (checked in the service). */
@Getter @Setter
public class UpsertAssignmentRuleRequest {
    @NotBlank private String domain;
    @NotNull private UUID targetId;
    @NotBlank @Size(max = 150) private String name;
    /** Lowest number is evaluated first. Null on create = after every existing rule. */
    private Integer priority;
    private UUID departmentId;
    private UUID locationId;
    private UUID shiftId;
    @Size(max = 30) private String employmentType;
    @Size(max = 100) private String grade;
    private Boolean active;
}
