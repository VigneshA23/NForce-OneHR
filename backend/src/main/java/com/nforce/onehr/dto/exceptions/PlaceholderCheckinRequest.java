package com.nforce.onehr.dto.exceptions;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

// TEMPORARY — delete with FR-004 (see PlaceholderCheckinSeed entity Javadoc).
@Data
public class PlaceholderCheckinRequest {

    @NotNull(message = "Employee is required")
    private UUID employeeUserId;

    @NotNull(message = "Work date is required")
    private LocalDate workDate;

    @NotNull(message = "Check-in time is required")
    private LocalTime checkinTime;

    // Defaults applied in ExceptionService when null.
    private LocalTime shiftStartTime;
    private Integer lateThresholdMinutes;
}
