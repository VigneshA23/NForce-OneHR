package com.nforce.onehr.dto.reports;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row shared by every Attendance Request Reports card (ONEHR-109) — checkOut is the
 * requested checkout for a regularization, or the actual checked-out-at time for a web clock-in.
 * requestMode/hours are populated only for Partial Day (partialDayMode/partialDayHours) and WFH
 * (partialDayMode as FULL_DAY|FIRST_HALF|SECOND_HALF / wfhDayFraction) rows; Overtime uses hours
 * for its requested duration. All other types leave them null.
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
    private String requestMode;
    private BigDecimal hours;
}
