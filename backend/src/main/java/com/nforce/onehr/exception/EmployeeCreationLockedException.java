package com.nforce.onehr.exception;

/**
 * Thrown by {@link com.nforce.onehr.service.EmployeeCodeGenerator#claim} while the temporary
 * employee-creation lock ({@code app.employee-creation.locked}) is enabled — e.g. during a live
 * Employee ID migration window. Distinct from {@link EmployeeCodeConflictException} so the API
 * can surface a different, non-retryable-right-now status instead of a per-code conflict.
 */
public class EmployeeCreationLockedException extends RuntimeException {

    public EmployeeCreationLockedException() {
        super("Employee creation is temporarily unavailable. Please try again later.");
    }
}
