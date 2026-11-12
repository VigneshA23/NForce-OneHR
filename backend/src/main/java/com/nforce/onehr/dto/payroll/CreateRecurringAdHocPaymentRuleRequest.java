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
public class CreateRecurringAdHocPaymentRuleRequest {

    /** Null = applies to every pay group. */
    private UUID payGroupId;

    @NotBlank
    private String componentName;

    @NotNull
    @DecimalMin(value = "0", inclusive = false, message = "amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank
    private String reason;
}
