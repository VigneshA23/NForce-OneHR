package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data @Builder
public class AttendanceResponse {
    private UUID id;
    private UUID employeeUserId;
    private String employeeCode;
    private String fullName;
    private LocalDate workDate;
    private LocalDateTime checkInAt;
    private LocalDateTime checkOutAt;
    private Integer workedMinutes;
    private String status;
    private Integer lateByMinutes;
    /** True once workedMinutes meets app.attendance.full-day-min-hours. Null while still open. */
    private Boolean fullDay;
    /** SYSTEM for a normal punch, REGULARIZATION if this row came from an approved correction. */
    private String source;
}
