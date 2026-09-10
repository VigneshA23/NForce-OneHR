package com.nforce.onehr.dto.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CreateAttendanceRequest {

    @NotBlank(message = "Request type is required")
    private String requestType; // WFH | PARTIAL_DAY

    @NotNull(message = "Request date is required")
    private LocalDate requestDate;

    // Required (and must be > 0) when requestType=PARTIAL_DAY; ignored/forced null for WFH —
    // see AttendanceRequestService.submit.
    private BigDecimal partialDayHours;

    // PARTIAL_DAY (required): LATE_ARRIVE | INTERVENING_TIMEOFF | LEAVING_EARLY.
    // WFH (optional, defaults to FULL_DAY): FULL_DAY | FIRST_HALF | SECOND_HALF.
    // See AttendanceRequestService.submit.
    private String partialDayMode;

    @NotBlank(message = "Reason is required")
    private String reason;

    // Optional — if omitted, the employee's current manager (EmployeeManagerHistory) is used.
    private UUID managerUserId;

    // Optional — a specific colleague to alert about this request (purely informational, not an
    // approver).
    private UUID notifyUserId;
}
