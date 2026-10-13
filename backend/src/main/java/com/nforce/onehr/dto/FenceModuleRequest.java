package com.nforce.onehr.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FenceModuleRequest {
    @NotBlank
    private String module;
    @NotBlank
    private String action;
}
