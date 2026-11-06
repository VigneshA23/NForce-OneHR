package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class CreateSalaryHoldRequest {

    @NotNull
    private UUID employeeId;

    @NotBlank
    private String reason;
}
