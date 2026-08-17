package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateDesignationRequest {

    @NotBlank(message = "Designation title is required")
    @Size(max = 100, message = "Designation title must be 100 characters or fewer")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])[A-Za-z0-9 \\-/]+$",
            message = "Designation title must include letters, and may only contain letters, numbers, spaces, - and /")
    private String title;

    @Size(max = 50, message = "Grade must be 50 characters or fewer")
    private String grade;

    @Size(max = 20)
    private String level;
}
