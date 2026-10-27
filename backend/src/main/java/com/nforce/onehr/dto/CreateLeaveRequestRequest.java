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

    /** Required when halfDay is true — FIRST_HALF or SECOND_HALF (see LeaveHalfDaySession). Null otherwise. */
    private String halfDaySession;

    @NotBlank
    private String reason;
}
