package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OvertimeRequestResponse {

    private UUID id;
    private UUID employeeUserId;
    private String employeeName;
    private String employeeEmail;
    private String departmentName;
    private LocalDate workDate;
    private LocalDateTime requestedStart;
    private LocalDateTime requestedEnd;
    /** Derived, not persisted — Duration.between(requestedStart, requestedEnd).toMinutes(). */
    private Long requestedMinutes;
    private String reason;
    private String status;
    private UUID assignedApproverId;
    private String assignedApproverName;
    private UUID notifyUserId;
    private String notifyUserName;
    private String reviewedByName;
    /** The reviewer's role at response time (e.g. "MANAGER", "HR_ADMIN", "SUPER_ADMIN") — lets
     * the "Last Action By" column show who acted in what capacity, since HR Admin/Super Admin can
     * decide a MANAGER-stage request too (see OvertimeRequestService's approver-override). */
    private String reviewedByRole;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private LocalDateTime createdAt;
}
