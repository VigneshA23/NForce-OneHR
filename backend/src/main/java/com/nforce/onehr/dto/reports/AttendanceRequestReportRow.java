package com.nforce.onehr.dto.reports;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row shared by the "Attendance Regularizations Summary", "Remote Clock-in Requests
 * Summary", "Remote Clock-ins", and "Web Clock-ins" report cards (ONEHR-109) — checkOut is the
 * requested checkout for a regularization, or the actual checked-out-at time for a web clock-in.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceRequestReportRow {

    private UUID employeeUserId;
    private String employeeCode;
    private String fullName;
    private LocalDate date;
    private LocalDateTime checkIn;
    private LocalDateTime checkOut;
    private String reason;
    private String status;
}
