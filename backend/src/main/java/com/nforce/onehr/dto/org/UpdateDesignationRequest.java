package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateDesignationRequest {
    @NotBlank(message = "Title is required")
    @Size(max = 100)
    private String title;

    @Size(max = 50)
    private String grade;

    @Size(max = 20)
    private String level;
}
