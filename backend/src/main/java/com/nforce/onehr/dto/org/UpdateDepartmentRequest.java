package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateDepartmentRequest {
    @NotBlank(message = "Name is required")
    @Size(max = 100)
    private String name;
}
