package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateBusinessUnitRequest {
    @NotBlank(message = "Name is required")
    @Size(max = 100)
    @Pattern(
            regexp = "^(?=.*[A-Za-z])[^0-9]+$",
            message = "Name must contain letters and cannot contain numbers or be made up of special characters only")
    private String name;
}
