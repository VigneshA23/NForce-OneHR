package com.nforce.onehr.dto.skills;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class EmployeeSkillResponse {
    private UUID id;
    // Present for every row created via the picker (Phase 3 onward); null for a pre-existing
    // free-text row that hasn't gone through Migration Review yet — skillName/proficiencyLevel
    // below are ALWAYS populated either way (resolved from the master list when the id is set,
    // the original free-text value otherwise), so this response never looks "broken" mid-migration.
    private UUID skillId;
    private String skillName;
    private UUID proficiencyLevelId;
    private String proficiencyLevel;
    private Instant createdAt;
    private Instant updatedAt;
}
