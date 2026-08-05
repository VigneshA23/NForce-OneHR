package com.nforce.onehr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateHolidayRequest {
    @NotBlank
    private String holidayName;

    @NotNull
    private LocalDate holidayDate;

    @NotNull
    private UUID locationId;
}
