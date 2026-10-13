package com.nforce.onehr.dto.skills;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Skills tab's picker: both ids are chosen from the Talent & Skills master lists
 * (skill_master/proficiency_level), not typed free text — see SkillTextNormalizer's own doc
 * comment and the Talent & Skills spec's section 0 for why free-text entry was replaced. */
@Data
public class EmployeeSkillRequest {
    @NotNull private UUID skillId;
    @NotNull private UUID proficiencyLevelId;
}
