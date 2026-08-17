package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreateOvertimeRequest {

    @NotNull(message = "Work date is required")
    private LocalDate workDate;

    @NotNull(message = "Requested start time is required")
    private LocalDateTime requestedStart;

    @NotNull(message = "Requested end time is required")
    private LocalDateTime requestedEnd;

    @NotBlank(message = "Reason is required")
    private String reason;

    // Optional — if omitted, the employee's current manager (EmployeeManagerHistory) is used.
    private UUID managerUserId;

    // Optional — a specific colleague to alert about this request (purely informational, not an
    // approver).
    private UUID notifyUserId;
}
