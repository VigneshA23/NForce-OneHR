package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** US-C02 — assigns (or revises) an employee's salary structure and CTC. */
@Getter
@Setter
public class AssignCompensationRequest {

    @NotNull
    private UUID employeeId;

    @NotNull
    private UUID payGroupId;

    @NotNull
    private UUID structureId;

    @NotNull
    @DecimalMin(value = "0", inclusive = false, message = "ctc must be greater than zero")
    private BigDecimal ctc;

    @NotNull
    private LocalDate effectiveFrom;
}
