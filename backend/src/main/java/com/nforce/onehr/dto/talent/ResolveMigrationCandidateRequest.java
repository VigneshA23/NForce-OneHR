package com.nforce.onehr.dto.talent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.UUID;

@Data
public class ResolveMigrationCandidateRequest {

    // MAP_EXISTING | CREATE_NEW | DISCARD
    @NotBlank
    @Pattern(regexp = "^(MAP_EXISTING|CREATE_NEW|DISCARD)$")
    private String action;

    // Required when action = MAP_EXISTING
    private UUID existingSkillId;

    // Optional, only used when action = CREATE_NEW
    private String category;
}
