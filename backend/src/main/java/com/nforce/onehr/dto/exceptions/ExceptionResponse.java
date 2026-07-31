package com.nforce.onehr.dto.exceptions;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Data @Builder
public class ExceptionResponse {
    private UUID id;
    private UUID employeeUserId;
    private String employeeCode;
    private String employeeFullName;
    private LocalDate exceptionDate;
    private String exceptionType;
    private LocalTime expectedTime;
    private LocalTime actualTime;
    private Integer minutesLate;
    private String status;
    private LocalDateTime detectedAt;
}
