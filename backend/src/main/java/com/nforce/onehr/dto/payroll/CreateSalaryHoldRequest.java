package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

    @NotBlank
    @Pattern(regexp = "PROCESSING|PAYOUT", message = "type must be PROCESSING or PAYOUT")
    private String type;
}
