package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Null {@code overrideAmount} clears an existing override, same convention as OverrideLopRequest. */
@Getter
@Setter
public class CreateStatutoryOverrideRequest {

    @NotNull
    private UUID employeeId;

    @NotNull
    private UUID payGroupId;

    private LocalDate referenceDate;

    @NotBlank
    private String ruleType;

    private BigDecimal overrideAmount;

    @NotBlank
    private String overrideReason;
}
