package com.nforce.onehr.dto.allocation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** US-B03: edit / release an allocation. Every field is optional; null leaves it unchanged. */
@Getter @Setter
public class ProjectAllocationUpdateRequest {

    @Min(value = 1, message = "Capacity percent must be between 1 and 100")
    @Max(value = 100, message = "Capacity percent must be between 1 and 100")
    private Integer capacityPercent;

    private LocalDate endDate;

    /** Releasing the resource early: the date the allocation really ends. */
    private LocalDate actualEndDate;
}
