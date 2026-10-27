package com.nforce.onehr.dto.bench;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** US-A13: band lookups and employee-to-band assignment (a label only, never a salary). */
public final class CompensationBandDtos {

    private CompensationBandDtos() {}

    public record BandRequest(@NotBlank String bandLabel, @NotNull @DecimalMin("0") BigDecimal avgCtc) {}

    public record BandResponse(UUID id, String bandLabel, BigDecimal avgCtc) {}

    public record AssignmentRequest(@NotNull UUID employeeId, UUID bandId) {}

    public record AssignmentResponse(UUID employeeId, String employeeName, UUID bandId, String bandLabel) {}
}
