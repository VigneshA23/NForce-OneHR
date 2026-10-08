package com.nforce.onehr.dto.allocation;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class CreateProjectRequest {

    @NotBlank(message = "Project name is required")
    private String name;
}
