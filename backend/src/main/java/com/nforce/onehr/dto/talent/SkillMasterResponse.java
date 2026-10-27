package com.nforce.onehr.dto.talent;

import com.nforce.onehr.entity.SkillMaster;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Value
public class SkillMasterResponse {
    UUID id;
    String name;
    String category;
    List<String> aliases;
    boolean active;
    long usageCount;
    Instant createdAt;
    Instant updatedAt;

    public static SkillMasterResponse from(SkillMaster s, List<String> aliases, long usageCount) {
        return new SkillMasterResponse(s.getId(), s.getName(), s.getCategory(), aliases,
                s.isActive(), usageCount, s.getCreatedAt(), s.getUpdatedAt());
    }
}
