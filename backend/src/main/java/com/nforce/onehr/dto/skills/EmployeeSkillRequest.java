package com.nforce.onehr.dto.skills;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class EmployeeSkillRequest {
    @NotBlank private String skillName;
    // Plain readable value, not a separate enum code — matches how gender/maritalStatus already
    // store their chosen label directly (see UpdateProfileRequest) rather than a code+label pair.
    @Pattern(regexp = "^(Beginner|Intermediate|Advanced|Expert)$", message = "Proficiency level must be Beginner, Intermediate, Advanced, or Expert")
    private String proficiencyLevel;
}
