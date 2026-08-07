package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateLocationRequest {

    @NotBlank(message = "Location name is required")
    @Size(max = 100, message = "Location name must be 100 characters or fewer")
    @Pattern(regexp = "^[^0-9]+$", message = "Location name cannot contain numbers")
    private String name;

    @Size(max = 100) private String city;
    @Size(max = 100) private String state;
    @Size(max = 100) private String country;
    @Size(max = 100) private String holidayRegion;
}
