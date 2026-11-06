package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** US-C05 — overrides the computed LOP days for a cycle. Null overrideLopDays clears the override (reverts to computed). */
@Getter
@Setter
public class OverrideLopRequest {

    @NotNull
    private UUID employeeId;

    @NotNull
    private UUID payGroupId;

    private LocalDate referenceDate;

    @DecimalMin(value = "0", message = "overrideLopDays cannot be negative")
    private BigDecimal overrideLopDays;

    @NotBlank
    private String overrideReason;
}
