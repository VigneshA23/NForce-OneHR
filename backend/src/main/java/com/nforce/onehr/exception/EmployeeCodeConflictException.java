package com.nforce.onehr.exception;

import lombok.Getter;

/**
 * Thrown by {@link com.nforce.onehr.service.EmployeeCodeGenerator#claim} when the submitted
 * Employee ID (the auto-populated suggestion, edited or not) is already assigned to another
 * employee — including the race where two concurrent requests submit the same ID.
 */
@Getter
public class EmployeeCodeConflictException extends RuntimeException {

    private final String requestedCode;

    public EmployeeCodeConflictException(String requestedCode) {
        super("Employee ID is unavailable. Please go back and retry.");
        this.requestedCode = requestedCode;
    }
}
