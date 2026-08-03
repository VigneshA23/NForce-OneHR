package com.nforce.onehr.dto.doc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PublishPolicyRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    @Size(max = 20)
    private String version;

    @NotBlank
    private String description;

    @Size(max = 200)
    private String audience = "All Employees";

    private boolean required = true;
}
