package com.nforce.onehr.dto.talent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSkillRequestRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 100)
    private String category;

    @Size(max = 500)
    private String note;
}
