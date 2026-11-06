package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** US-C08 — generates (or regenerates, while still DRAFT) the payslips for the pay cycle containing referenceDate. */
@Getter
@Setter
public class GenerateDraftRequest {

    @NotNull
    private UUID payGroupId;

    private LocalDate referenceDate;
}
