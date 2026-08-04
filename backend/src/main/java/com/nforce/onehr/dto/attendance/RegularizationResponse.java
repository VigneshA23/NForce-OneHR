package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RegularizationResponse {

    private UUID id;
    private UUID employeeUserId;
    private String employeeName;
    private String employeeEmail;
    private String departmentName;
    private LocalDate attendanceDate;
    private LocalDateTime requestedCheckIn;
    private LocalDateTime requestedCheckOut;
    private String reason;
    private String status;
    private UUID assignedApproverId;
    private String assignedApproverName;
    // Requested-checkout minus requested-checkin, in minutes. Null unless both times are set.
    private Long totalMinutes;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private LocalDateTime createdAt;
    @Builder.Default
    private List<ApprovalHistoryEntryDto> approvalHistory = List.of();
}
