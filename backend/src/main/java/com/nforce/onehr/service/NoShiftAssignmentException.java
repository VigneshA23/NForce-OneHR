package com.nforce.onehr.service;

/**
 * Thrown by {@link EmployeeShiftAssignmentResolver#resolve} specifically when an employee has no
 * {@code EmployeeShiftAssignment} effective on or before the requested date — a distinct subtype
 * of {@link IllegalStateException} purely so callers that treat "no assignment for this date" as
 * an expected, non-corrupt condition (e.g. {@link AttendanceInterpretationService#belongsToWorkday}/
 * {@link AttendanceInterpretationService#resolveWorkdayWindowFor} degrading to a plain calendar-date
 * check) can catch exactly that case, without also swallowing the plain {@link IllegalStateException}
 * {@link ShiftVersionResolver#resolve} throws for a genuine Shift/ShiftVersion invariant violation —
 * that one must keep propagating loudly, never silently degraded. Behaves identically to
 * {@link IllegalStateException} for every existing caller that doesn't specifically catch this
 * subtype (e.g. {@code GlobalExceptionHandler}'s {@code IllegalStateException} mapping).
 */
public class NoShiftAssignmentException extends IllegalStateException {

    public NoShiftAssignmentException(String message) {
        super(message);
    }
}
