package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** US-C07 — recomputes joiner/exit pro-ration for the pay cycle (resolved via payGroupId + referenceDate) containing referenceDate. */
@Getter
@Setter
public class RecomputeProrationRequest {

    @NotNull
    private UUID employeeId;

    @NotNull
    private UUID payGroupId;

    /** Defaults to today when omitted. */
    private LocalDate referenceDate;
}
