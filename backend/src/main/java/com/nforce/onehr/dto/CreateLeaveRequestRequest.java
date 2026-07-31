package com.nforce.onehr.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateLeaveRequestRequest {
    @NotBlank
    private String leaveTypeCode;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    private boolean halfDay = false;

    @NotBlank
    private String reason;
}
