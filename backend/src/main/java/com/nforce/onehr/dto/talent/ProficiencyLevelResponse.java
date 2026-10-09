package com.nforce.onehr.dto.talent;

import com.nforce.onehr.entity.ProficiencyLevel;
import lombok.Value;

import java.util.UUID;

@Value
public class ProficiencyLevelResponse {
    UUID id;
    String name;
    int sortOrder;
    String description;
    boolean active;
    long usageCount;

    public static ProficiencyLevelResponse from(ProficiencyLevel p, long usageCount) {
        return new ProficiencyLevelResponse(p.getId(), p.getName(), p.getSortOrder(), p.getDescription(), p.isActive(), usageCount);
    }
}
