package com.nforce.onehr.dto.talent;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Merges {@code sourceSkillId} into {@code targetSkillId}: every employee_skills row and
 * skill_alias pointing at the source is repointed at the target, the source's own name is kept
 * as a new alias of the target (so searches for the old name still resolve), and the source skill
 * is deactivated rather than deleted. */
@Data
public class MergeSkillsRequest {

    @NotNull
    private UUID sourceSkillId;

    @NotNull
    private UUID targetSkillId;
}
