package com.nforce.onehr.dto.allocation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** US-B10: same project/capacity/date-range applied to every selected employee in one request. */
@Getter @Setter
public class BulkProjectAllocationRequest {

    @NotEmpty(message = "At least one employee must be selected")
    private List<UUID> employeeIds;

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
