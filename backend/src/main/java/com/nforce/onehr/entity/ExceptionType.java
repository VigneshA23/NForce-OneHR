package com.nforce.onehr.entity;

/**
 * Allowed values for AttendanceException.exceptionType. Plain constants, not a
 * JPA enum, matching the codebase's convention of storing classification codes
 * as plain strings (see Role.code, Employee.workMode).
 */
public final class ExceptionType {

    public static final String LATE_ARRIVAL = "LATE_ARRIVAL";

    // A prior day's row with a check-in but no check-out.
    public static final String MISSING_PUNCH = "MISSING_PUNCH";

    // An approved leave request covers this date, yet a check-in/check-out was also recorded.
    public static final String LEAVE_ATTENDANCE_CONFLICT = "LEAVE_ATTENDANCE_CONFLICT";

    private ExceptionType() {}
}
