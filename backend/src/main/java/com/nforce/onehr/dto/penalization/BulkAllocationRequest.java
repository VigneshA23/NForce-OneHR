package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
public class BulkAllocationRequest {
    @NotEmpty
    private List<UUID> employeeUserIds;
    @NotNull
    private UUID penalisationPolicyId;
    @NotNull
    private LocalDate effectiveFrom;
    /** Null = no end date (open-ended). */
    private LocalDate effectiveTo;
}
