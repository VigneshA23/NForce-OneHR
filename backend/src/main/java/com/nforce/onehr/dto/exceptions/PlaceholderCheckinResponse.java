package com.nforce.onehr.dto.exceptions;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

// TEMPORARY — delete with FR-004 (see PlaceholderCheckinSeed entity Javadoc).
@Data @Builder
public class PlaceholderCheckinResponse {
    private UUID id;
    private UUID employeeUserId;
    private String employeeFullName;
    private LocalDate workDate;
    private LocalTime shiftStartTime;
    private LocalTime checkinTime;
    private Integer lateThresholdMinutes;
    private LocalDateTime createdAt;
}
