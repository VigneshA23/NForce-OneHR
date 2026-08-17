package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateDesignationRequest {
    @NotBlank(message = "Title is required")
    @Size(max = 100)
    @Pattern(
            regexp = "^(?=.*[A-Za-z])[A-Za-z0-9 \\-/]+$",
            message = "Title must include letters, and may only contain letters, numbers, spaces, - and /")
    private String title;

    @Size(max = 50)
    private String grade;

    @Size(max = 20)
    private String level;
}
