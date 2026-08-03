package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreateRegularizationRequest {

    @NotNull(message = "Attendance date is required")
    private LocalDate attendanceDate;

    private LocalDateTime requestedCheckIn;

    private LocalDateTime requestedCheckOut;

    @NotBlank(message = "Reason is required")
    private String reason;

    // Optional — if omitted, the employee's current manager (EmployeeManagerHistory) is used.
    private UUID managerUserId;
}
