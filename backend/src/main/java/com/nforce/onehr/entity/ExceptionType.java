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

    // Reserved — no working day with zero attendance is detected as an exception yet
    // (ExceptionService.detectExceptions doesn't produce this today). Listed here as the
    // authoritative discrepancy identifier for AttendancePolicyEngine/AttendancePenalty
    // consumers so a future detector and the policy layer agree on the same string.
    public static final String NO_ATTENDANCE = "NO_ATTENDANCE";

    // Reserved — a worked day short of the expected hours is not detected as an exception yet.
    public static final String WORK_HOURS_SHORTAGE = "WORK_HOURS_SHORTAGE";

    // Reserved — an early checkout is not detected as an exception yet.
    public static final String EARLY_DEPARTURE = "EARLY_DEPARTURE";

    // A leave request still awaiting approval. Synthesized directly from LeaveRequest at
    // Exception Dashboard read time (see ExceptionService.getExceptionsForCaller) — never
    // persisted to attendance_exceptions and never evaluated against a Penalization Policy,
    // since it isn't an attendance discrepancy.
    public static final String PENDING_LEAVE_APPROVAL = "PENDING_LEAVE_APPROVAL";

    private ExceptionType() {}
}
