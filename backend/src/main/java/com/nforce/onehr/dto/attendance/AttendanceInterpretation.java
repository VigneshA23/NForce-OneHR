package com.nforce.onehr.dto.attendance;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The output of {@link com.nforce.onehr.service.AttendanceInterpretationService} — everything a
 * caller (AttendanceService/WebClockInService/RegularizationService) needs to finalize a punch or
 * decide staleness, without any of them re-deriving Shift-relative facts themselves. Which fields
 * are populated depends on which of the service's two entry points produced it (mirrors
 * {@link PolicyEvaluationContext}'s own established convention of a single fact-carrying type with
 * fields that only apply to some callers):
 * <ul>
 *   <li>{@code interpretFreshAction} populates {@code workDate}/{@code isLate}/
 *       {@code lateByMinutes}/{@code shiftId} — {@code checkoutCutoff}/{@code maximumBoundary}
 *       are null (there is no existing session yet to bound).</li>
 *   <li>{@code interpretExistingSession} populates {@code workDate} (the shift-day {@code now}
 *       resolves to, for the caller's own staleness comparison against the record's stored
 *       {@code workDate} — this class never makes that comparison itself), {@code checkoutCutoff},
 *       {@code maximumBoundary}, and {@code shiftId} (the record's own snapshot, echoed back) —
 *       {@code isLate}/{@code lateByMinutes} are null (lateness for an existing record was already
 *       fixed at its own creation and is never recomputed here).</li>
 * </ul>
 * {@code outcome} is {@link InterpretationOutcome#LEGACY_UNRESOLVED} only for
 * {@code interpretExistingSession} on a row with no {@code shiftId} snapshot — every other field
 * is then null, and the caller MUST degrade explicitly rather than substitute anything.
 *
 * <p>{@code outcome} is {@link InterpretationOutcome#NO_SHIFT_ASSIGNED} for
 * {@code interpretFreshAction}/{@code interpretForKnownWorkDate} when the employee has no
 * {@code EmployeeShiftAssignment} effective on the date in question (a valid, permanent state —
 * see that enum value's own Javadoc). Only {@code workDate} (the plain calendar date) is
 * populated; {@code isLate}/{@code lateByMinutes}/{@code shiftId} are null. Callers must record
 * an ordinary PRESENT day with no shift interpretation, never substitute the employee's current
 * Shift or throw.
 */
@Value
@Builder
public class AttendanceInterpretation {
    InterpretationOutcome outcome;
    LocalDate workDate;
    Boolean isLate;
    Integer lateByMinutes;
    LocalDateTime checkoutCutoff;
    LocalDateTime maximumBoundary;
    UUID shiftId;

    public static AttendanceInterpretation legacyUnresolved() {
        return AttendanceInterpretation.builder().outcome(InterpretationOutcome.LEGACY_UNRESOLVED).build();
    }

    /**
     * A fresh action with no effective {@code EmployeeShiftAssignment} as of {@code workDate} —
     * see {@link InterpretationOutcome#NO_SHIFT_ASSIGNED}'s own Javadoc. {@code workDate} is the
     * only populated field (the plain calendar date); every Shift-dependent fact is deliberately
     * left null rather than guessed.
     */
    public static AttendanceInterpretation noShiftAssigned(LocalDate workDate) {
        return AttendanceInterpretation.builder().outcome(InterpretationOutcome.NO_SHIFT_ASSIGNED).workDate(workDate).build();
    }

    public boolean isLegacyUnresolved() {
        return outcome == InterpretationOutcome.LEGACY_UNRESOLVED;
    }

    public boolean isNoShiftAssigned() {
        return outcome == InterpretationOutcome.NO_SHIFT_ASSIGNED;
    }

    /**
     * The single, shared derivation every caller (AttendanceService/WebClockInService/
     * RegularizationService) needs when finalizing a punch's isLate/lateByMinutes/shiftId —
     * degrading all three to their "ordinary PRESENT day, no shift interpretation" values for
     * {@link InterpretationOutcome#NO_SHIFT_ASSIGNED}, never guessed or fabricated, exactly as
     * this class's own Javadoc already documents. Centralized here (rather than each caller
     * re-deriving the identical {@code noShift ? ... : ...} triplet) so a future caller can't
     * forget one of the three and accidentally persist a real shiftId/lateByMinutes alongside a
     * NO_SHIFT_ASSIGNED outcome. Callers still handle {@link #isLegacyUnresolved()} themselves
     * (it means something different per caller — see this class's own Javadoc), so these are only
     * ever read once that's already been ruled out.
     */
    public boolean effectiveIsLate() {
        return !isNoShiftAssigned() && Boolean.TRUE.equals(isLate);
    }

    /** @see #effectiveIsLate() */
    public int effectiveLateByMinutes() {
        return isNoShiftAssigned() || lateByMinutes == null ? 0 : lateByMinutes;
    }

    /** @see #effectiveIsLate() */
    public UUID effectiveShiftId() {
        return isNoShiftAssigned() ? null : shiftId;
    }
}
