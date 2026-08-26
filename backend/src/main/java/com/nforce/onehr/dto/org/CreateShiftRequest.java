package com.nforce.onehr.dto.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Data
public class CreateShiftRequest {

    @NotBlank(message = "Shift name is required")
    @Size(max = 100)
    private String name;

    @Size(max = 30)
    private String code;

    @Size(max = 2000)
    private String description;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    @NotNull(message = "End time is required")
    private LocalTime endTime;

    private boolean flexible;

    private Integer breakMinutes;

    /** java.time.DayOfWeek names, e.g. ["MONDAY", "TUESDAY"]. Null/empty = not specified at the shift level. */
    private List<String> workingDays;
}
