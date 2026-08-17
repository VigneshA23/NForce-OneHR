package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClonePenalisationPolicyRequest {

    @NotBlank(message = "Name is required")
    private String name;

    private String description;
}
