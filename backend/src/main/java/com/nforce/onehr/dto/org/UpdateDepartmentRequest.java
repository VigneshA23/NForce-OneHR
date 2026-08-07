package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateDepartmentRequest {
    @NotBlank(message = "Name is required")
    @Size(max = 100)
    @Pattern(regexp = "^[^0-9]+$", message = "Name cannot contain numbers")
    private String name;
}
