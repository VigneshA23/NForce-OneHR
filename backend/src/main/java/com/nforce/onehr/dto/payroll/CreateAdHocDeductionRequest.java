package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
public class CreateAdHocDeductionRequest {

    @NotNull
    private UUID employeeId;

    // Must resolve to an active, AD_HOC_DEDUCTION-type SalaryComponent catalog entry.
    @NotBlank
    private String componentName;

    @NotNull
    @DecimalMin(value = "0", inclusive = false, message = "totalAmount must be greater than zero")
    private BigDecimal totalAmount;

    @NotNull
    @DecimalMin(value = "0", inclusive = false, message = "installmentAmount must be greater than zero")
    private BigDecimal installmentAmount;

    @NotBlank
    private String reason;
}
