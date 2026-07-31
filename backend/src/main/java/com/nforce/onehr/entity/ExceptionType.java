package com.nforce.onehr.entity;

/**
 * Allowed values for AttendanceException.exceptionType. Plain constants, not a
 * JPA enum, matching the codebase's convention of storing classification codes
 * as plain strings (see Role.code, Employee.workMode).
 */
public final class ExceptionType {

    public static final String LATE_ARRIVAL = "LATE_ARRIVAL";

    // MISSING_PUNCH is intentionally not implemented yet — out of scope for this
    // branch. Add it here (additive, no schema change) once FR-004 (Attendance
    // Management) provides real check-out data to detect it against.

    private ExceptionType() {}
}
