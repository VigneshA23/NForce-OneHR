package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only mirror of AttendanceException, powering the Attendance Log's PENALTY badge.
 * Always empty today — nothing in the codebase writes AttendanceException rows yet. Wired
 * passively so the badge lights up automatically once a detection job/service exists; do not
 * add penalty-detection business logic here or in AttendanceService.getMyExceptions.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceExceptionResponse {

    private UUID id;
    private LocalDate exceptionDate;
    private String exceptionType;
    private String status;
    private Integer minutesLate;
}
