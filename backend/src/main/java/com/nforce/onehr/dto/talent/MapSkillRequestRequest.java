package com.nforce.onehr.dto.talent;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class MapSkillRequestRequest {

    @NotNull
    private UUID existingSkillId;
}
