package com.nforce.onehr.dto.talent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateSkillMasterRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 100)
    private String category;

    // Optional alternate spellings to seed alongside the skill itself (e.g. creating "Java" with
    // aliases ["Core Java", "JDK"]) — equivalent to, but faster than, adding them one at a time
    // via POST /api/org/talent/skills/{id}/aliases afterward.
    private List<String> aliases;
}
