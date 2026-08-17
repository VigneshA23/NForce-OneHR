package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceRequestResponse {

    private UUID id;
    private UUID employeeUserId;
    private String employeeName;
    private String employeeEmail;
    private String departmentName;
    private String requestType;
    private LocalDate requestDate;
    private BigDecimal partialDayHours;
    private String partialDayMode;
    private String reason;
    private String status;
    private UUID assignedApproverId;
    private String assignedApproverName;
    private UUID notifyUserId;
    private String notifyUserName;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private LocalDateTime createdAt;
}
