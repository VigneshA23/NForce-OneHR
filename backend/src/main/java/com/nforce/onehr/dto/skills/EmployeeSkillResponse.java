package com.nforce.onehr.dto.skills;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class EmployeeSkillResponse {
    private UUID id;
    private String skillName;
    private String proficiencyLevel;
    private Instant createdAt;
    private Instant updatedAt;
}
