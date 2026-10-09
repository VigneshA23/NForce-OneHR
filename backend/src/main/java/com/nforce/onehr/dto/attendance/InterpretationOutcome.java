package com.nforce.onehr.dto.attendance;

/**
 * Every outcome {@link com.nforce.onehr.service.AttendanceInterpretationService} may return. Do
 * not add a third value without a corresponding product decision — the whole point of this being
 * closed is that a caller can never silently treat an unresolved interpretation as if it were a
 * normal one.
 */
public enum InterpretationOutcome {
    /** Resolved against a real, known Shift — either the employee's current one (a fresh action)
     *  or the Shift snapshotted on the Attendance row being interpreted (an existing session). */
    RESOLVED,
    /**
     * The Attendance row being interpreted predates the {@code shiftId} snapshot column
     * (created before this Shift/Attendance decoupling shipped) and has no recorded Shift
     * context. Deliberately NEVER resolved by substituting the employee's current Shift — see
     * AttendanceInterpretationService's own Javadoc. Callers must degrade explicitly (e.g. treat
     * an open legacy session as unresolved for staleness/cutoff purposes, or surface that a
     * historical correction's lateness cannot be safely recomputed) rather than guess.
     */
    LEGACY_UNRESOLVED,
    /**
     * A FRESH action ({@code interpretFreshAction}/{@code interpretForKnownWorkDate}) for an
     * employee with no {@code EmployeeShiftAssignment} effective on the date in question — a
     * valid, permanent state (a brand-new employee with no Shift selected at all, or one whose
     * first assignment is not yet effective — see {@code EmployeeShiftAssignmentResolver}),
     * never the invariant violation {@code EmployeeShiftAssignmentResolver#resolve} guards
     * against. {@code workDate} is the plain calendar date (no shift-relative rollover is
     * possible without a Shift to roll against); {@code isLate}/{@code lateByMinutes}/
     * {@code shiftId} are all null — no Shift-dependent fact is computed, and none may be
     * guessed. Callers must record attendance as an ordinary PRESENT day with no shift
     * interpretation (no lateness, no late penalty) rather than treat this as an error.
     */
    NO_SHIFT_ASSIGNED
}
