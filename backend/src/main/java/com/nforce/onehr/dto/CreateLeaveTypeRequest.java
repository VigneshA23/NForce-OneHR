package com.nforce.onehr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateLeaveTypeRequest {

    @NotBlank
    @Size(max = 20)
    private String code;

    @NotBlank
    @Size(max = 50)
    private String name;

    @NotBlank
    @Pattern(regexp = "PAID|UNPAID", message = "classification must be PAID or UNPAID")
    private String classification;
}
