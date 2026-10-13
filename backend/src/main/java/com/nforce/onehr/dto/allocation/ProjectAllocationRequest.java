package com.nforce.onehr.dto.allocation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** US-B04: single-employee project allocation request. */
@Getter @Setter
public class ProjectAllocationRequest {

    @NotNull(message = "Employee is required")
    private UUID employeeId;

    @NotNull(message = "Project is required")
    private UUID projectId;

    @NotNull(message = "Capacity percent is required")
    @Min(value = 1, message = "Capacity percent must be between 1 and 100")
    @Max(value = 100, message = "Capacity percent must be between 1 and 100")
    private Integer capacityPercent;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    private LocalDate endDate;
}
