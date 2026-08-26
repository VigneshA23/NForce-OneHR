package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateDepartmentRequest {

    @NotBlank(message = "Department name is required")
    @Size(max = 100, message = "Department name must be 100 characters or fewer")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])[^0-9]+$",
            message = "Department name must contain letters and cannot contain numbers or be made up of special characters only")
    private String name;
}
