package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendanceContext;
import com.nforce.onehr.dto.attendance.AttendanceInterpretation;
import com.nforce.onehr.dto.attendance.InterpretationOutcome;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The single, narrow, shared owner of Shift-relative punch interpretation — replacing what used
 * to be three independently-duplicated implementations across {@link AttendanceService}
 * ({@code checkIn}/{@code checkOut}/{@code flagMissingCheckoutIfStale}), {@link WebClockInService}
 * (its own private {@code recomputeDerivedFields}), and {@link RegularizationService} (its own
 * private {@code recomputeDerivedFields}/{@code resolveShiftStart}).
 *
 * <p><b>Exactly three responsibilities, nothing more</b> — see the class-level design discussion
 * this was built from:
 * <ol>
 *   <li>Work-date attribution — delegates entirely to {@link ShiftDayPolicy#shiftDayOf}.</li>
 *   <li>Lateness — resolves the Shift Version via {@link ShiftDayPolicy#resolveShiftStart}/
 *       {@link ShiftDayPolicy#resolveLateGraceMinutes} (both backed by {@link ShiftVersionResolver},
 *       so grace is per-Shift-Version, not a single global value — see V168), and returns the
 *       same two facts {@link AttendanceService#checkIn} always has: a grace-aware {@code isLate}
 *       and a raw, no-forgiveness {@code lateByMinutes}.</li>
 *   <li>Checkout/staleness boundary — {@link ShiftDayPolicy#shiftEndAt} and
 *       {@link ShiftDayPolicy#maximumAttendanceBoundary}.</li>
 * </ol>
 * It does NOT compute worked duration, actual break, expected hours, or anything Tracking-Policy-
 * related — those already have their own, unduplicated owners ({@link AttendanceService}'s own
 * worked-minutes/break arithmetic, {@link ExpectedWorkHoursService}, {@link ExceptionService}/
 * {@link ConfiguredAttendancePolicyEngine}) and are not touched by this class.
 *
 * <h2>Four entry points, never confused about which Shift they resolve against</h2>
 * <ul>
 *   <li>{@link #interpretFreshAction} — a brand-new Check-In/Web Clock-In click: no prior
 *       Attendance context exists for the date {@code context.getNow()} resolves to. Derives the
 *       work-date from that timestamp AND resolves lateness, both against the employee's CURRENT
 *       Shift — correct, since there is nothing to preserve yet.</li>
 *   <li>{@link #interpretForKnownWorkDate} — a Regularization-created row for a date with no
 *       prior punch: the work-date is already known (the employee-picked correction date), so
 *       there is nothing to derive — only lateness is resolved, against the employee's CURRENT
 *       Shift (again correct: no prior context exists for that date either).</li>
 *   <li>{@link #interpretExistingSession} — an open session being checked out or swept for
 *       staleness: resolves work-day/cutoff/staleness-boundary against THAT ROW's own snapshotted
 *       {@code shiftId}, never the employee's current Shift.</li>
 *   <li>{@link #interpretExistingRecordLateness} — a Regularization correction to an ALREADY
 *       EXISTING row (its check-in time is being changed): resolves lateness against THAT ROW's
 *       own snapshotted {@code shiftId}, never the employee's current Shift — the direct fix for
 *       "employee reassigned since this historical record's date" silently changing a correction's
 *       computed lateness.</li>
 * </ul>
 * The dividing line is never "which method is convenient" — it's always "does an Attendance row
 * already exist for this work-date." If yes, its own {@code shiftId} governs, full stop.
 *
 * <h2>Legacy rows — explicit, never guessed</h2>
 * A row with {@code shiftId == null} means one of two things, disambiguated by
 * {@code Attendance.noShiftAssigned} (see that field's own Javadoc): a genuine legacy row
 * predating the shiftId column entirely (see V163's migration comment), for which both
 * {@link #interpretExistingSession} and {@link #interpretExistingRecordLateness} return
 * {@link InterpretationOutcome#LEGACY_UNRESOLVED} with every other field null; or a valid, current
 * NO_SHIFT_ASSIGNED row (see the section below), for which both methods return
 * {@link InterpretationOutcome#NO_SHIFT_ASSIGNED} instead — never conflated, since a valid no-
 * Shift attendance row must remain regularizable/stale-detectable like any other, while a genuine
 * legacy row must not. Neither case is ever resolved by falling back to the employee's current
 * Shift (that would silently reintroduce the exact historical-corruption problem this whole design
 * exists to prevent) or guessed via a plain calendar-date rollover where a Shift context IS known
 * (a genuinely overnight Shift makes that guess provably wrong) — LEGACY_UNRESOLVED specifically
 * leaves every field null; NO_SHIFT_ASSIGNED explicitly degrades to a plain calendar-date
 * attribution instead, there being no Shift at all to roll against. Callers decide what each
 * outcome means for their own operation — e.g. an open legacy session is left alone by the
 * existing MISSING_CHECKOUT mechanism rather than assigned a fabricated cutoff, while an open
 * no-Shift session participates in that same mechanism normally; a historical correction on a
 * legacy row surfaces the outcome rather than presenting a computed lateness figure as reliable,
 * while one on a no-Shift row is approved as an ordinary PRESENT day.
 *
 * <h2>No Shift Assignment — a valid state, never an error</h2>
 * A brand-new employee may legitimately have no {@code EmployeeShiftAssignment} at all (no Shift
 * was ever selected for them), or one whose first assignment is not yet effective (it takes
 * effect on their next working day, never the creation/joining day — see
 * {@code EmployeeService#createEmployee}/{@code UserManagementService#createUser}). {@link
 * #interpretFreshAction}/{@link #interpretForKnownWorkDate} check
 * {@link EmployeeShiftAssignmentResolver#resolveIfPresent} FIRST, before ever calling into
 * {@link ShiftDayPolicy} — an employee in either state above returns
 * {@link InterpretationOutcome#NO_SHIFT_ASSIGNED} (plain calendar-date work-date attribution, no
 * lateness, no shiftId), never a fabricated Shift and never a thrown exception.
 *
 * <h2>A genuine invariant violation — loud, never silently degraded</h2>
 * Once an effective {@link com.nforce.onehr.entity.EmployeeShiftAssignment} actually exists for
 * the date in question, its own {@link Shift}/{@link com.nforce.onehr.entity.ShiftVersion} data
 * is expected to be genuinely resolvable — {@link EmployeeShiftAssignmentResolver#resolve}/
 * {@link ShiftVersionResolver#resolve} guard exactly that invariant (an assignment referencing a
 * Shift with no version covering the date, for instance). If either ever throws here regardless,
 * this class does NOT catch it and does NOT substitute a plain calendar-date attribution (a
 * 22:00→07:00 overnight employee would be silently misattributed to the wrong logical workday) —
 * the exception propagates as-is, a loud, typed, alertable failure, clearly distinct from the
 * ordinary NO_SHIFT_ASSIGNED case above.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceInterpretationService {

    private final ShiftDayPolicy shiftDayPolicy;
    private final ShiftRepository shiftRepository;
    // Only for the day-aware interpretFreshAction/interpretForKnownWorkDate(UUID, ...) overload
    // below — never consulted for a historical row, which always resolves via its own snapshotted
    // shiftId (resolveShiftContextOrNull) instead.
    private final EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver;

    /**
     * A brand-new Check-In/Web Clock-In click — derives the work-date from {@code context.getNow()}
     * and delegates to {@link #interpretForKnownWorkDate(UUID, LocalDate, LocalDateTime)} for
     * lateness against that same date/instant, both resolved via the employee's Shift Assignment
     * effective on that specific date (never {@code Employee.shift}, a best-effort display cache —
     * see that field's own Javadoc) — correct here since there is nothing to preserve yet.
     */
    @Transactional(readOnly = true)
    public AttendanceInterpretation interpretFreshAction(Employee employee, AttendanceContext context) {
        // Checked BEFORE any shift-relative computation — ShiftDayPolicy#shiftDayOf has no
        // fallback for "no assignment at all" (by design: it guards a genuine invariant
        // violation for a Shift ALREADY on the employee). A brand-new/no-shift employee, or one
        // whose first assignment isn't effective yet, is a valid, permanent state, not that
        // invariant violation — see InterpretationOutcome#NO_SHIFT_ASSIGNED. Work-date
        // attribution falls back to the plain calendar date (there is no Shift to roll an
        // overnight boundary against) and no Shift-dependent fact is computed.
        LocalDate calendarDate = context.getNow().toLocalDate();
        Optional<EmployeeShiftAssignment> assignment =
                employeeShiftAssignmentResolver.resolveIfPresent(employee.getUserId(), calendarDate);
        if (assignment.isEmpty()) {
            return AttendanceInterpretation.noShiftAssigned(calendarDate);
        }
        // Passed through rather than re-resolved: ShiftDayPolicy#shiftDayOf would otherwise
        // immediately re-query this exact employee+date pair for Rule 1 (today's own start) — see
        // that overload's own Javadoc. Rule 2 (yesterday's boundary), if it applies, still
        // resolves yesterday's assignment independently; this never reuses today's for that.
        LocalDate workDate = shiftDayPolicy.shiftDayOf(employee.getUserId(), context.getNow(), assignment.get());
        if (workDate.equals(calendarDate)) {
            return interpretForKnownWorkDate(pin(employee.getUserId(), assignment.get()), workDate, context.getNow());
        }
        // The rarer overnight-rollover case: workDate is yesterday relative to calendarDate, so
        // today's already-resolved assignment must NOT be reused for it — falls through to
        // interpretForKnownWorkDate's own independent resolution for that (different) date.
        return interpretForKnownWorkDate(employee.getUserId(), workDate, context.getNow());
    }

    /**
     * Lateness for an employee/work-date/check-in-instant that's already known, resolved against
     * the Shift Assignment effective on {@code workDate} itself (never {@code Employee.shift}) —
     * used for (a) {@link #interpretFreshAction}'s own delegation above, and (b) a
     * Regularization-created row for a date with no prior punch, where the work-date is the
     * employee-picked correction date (which may be backdated — this must resolve against
     * whichever Shift governed THAT date, not whichever the employee is on today). Correct in
     * both cases, since neither has any prior Attendance context to preserve; delegates to the
     * pinned-{@link Employee} overload below once the right Shift for {@code workDate} is known.
     */
    @Transactional(readOnly = true)
    public AttendanceInterpretation interpretForKnownWorkDate(UUID employeeUserId, LocalDate workDate, LocalDateTime checkInAt) {
        // Same no-assignment guard as interpretFreshAction above (which delegates here) — also
        // covers a Regularization-created row for a date with no prior punch, backdated to a
        // date before the employee's first assignment (or a still-fully-shift-less employee).
        Optional<EmployeeShiftAssignment> assignment = employeeShiftAssignmentResolver.resolveIfPresent(employeeUserId, workDate);
        if (assignment.isEmpty()) {
            return AttendanceInterpretation.noShiftAssigned(workDate);
        }
        // Pinned directly from the assignment just resolved above, rather than pinForDate (which
        // would re-resolve the identical employeeUserId+workDate pair a second time).
        return interpretForKnownWorkDate(pin(employeeUserId, assignment.get()), workDate, checkInAt);
    }

    /**
     * Same lateness formula as the {@code UUID}-taking overload above, for an ALREADY-RESOLVED
     * Shift context — used exclusively by {@link #interpretExistingRecordLateness} with a
     * historical row's own pinned snapshot (see {@link #resolveShiftContextOrNull}); never called
     * directly with a real, live {@link Employee} (whose {@code .getShift()} is a best-effort
     * display cache, not necessarily the assignment effective on {@code workDate} — see the
     * {@code UUID}-taking overload for that case). Mirrors the exact formula
     * {@link AttendanceService#checkIn} has always used: {@code isLate} is grace-aware
     * (deadline = shiftStart + grace), {@code lateByMinutes} is the raw, no-forgiveness minutes
     * past shiftStart itself (an employee-facing display value, never grace-forgiven) — both
     * anchored to full date-aware instants, never a bare {@link LocalTime}, so an overnight
     * shift's post-midnight arrival is measured correctly.
     */
    private AttendanceInterpretation interpretForKnownWorkDate(Employee employee, LocalDate workDate, LocalDateTime checkInAt) {
        LocalTime shiftStart = shiftDayPolicy.resolveShiftStart(employee, workDate);
        LocalDateTime shiftStartAt = LocalDateTime.of(workDate, shiftStart);
        int graceMinutes = shiftDayPolicy.resolveLateGraceMinutes(employee, workDate);
        LocalDateTime deadlineAt = shiftStartAt.plusMinutes(graceMinutes);
        boolean isLate = checkInAt.isAfter(deadlineAt);
        int lateByMinutes = checkInAt.isAfter(shiftStartAt)
                ? (int) Duration.between(shiftStartAt, checkInAt).toMinutes()
                : 0;
        return AttendanceInterpretation.builder()
                .outcome(InterpretationOutcome.RESOLVED)
                .workDate(workDate)
                .isLate(isLate)
                .lateByMinutes(lateByMinutes)
                .shiftId(employee.getShift().getId())
                .build();
    }

    /**
     * For an already-created Attendance row (an open session being checked out or swept for
     * staleness) — resolves work-day/checkout-cutoff/staleness-boundary against THAT ROW's own
     * snapshotted {@code shiftId}, never the employee's current Shift. {@code now} is the caller's
     * already-resolved "current instant" (from the record's own locked-in timezone — see
     * {@code resolveZone} — unrelated to this method).
     *
     * <p>{@code shiftId == null} is NO_SHIFT_ASSIGNED (never LEGACY_UNRESOLVED) when
     * {@code record.isNoShiftAssigned()} — see that field's own Javadoc. There is no Shift to roll
     * an overnight boundary against, so {@code workDate} degrades to {@code now}'s own plain
     * calendar date — the EXISTING staleness comparison in
     * {@code AttendanceService#flagMissingCheckoutIfStale} (this outcome's {@code workDate} vs. the
     * record's own stored {@code workDate}) still applies completely unmodified: the session goes
     * stale the moment the calendar date rolls over, exactly the same mechanism a shift-assigned
     * employee's session uses, just without a Shift to roll against.
     */
    @Transactional(readOnly = true)
    public AttendanceInterpretation interpretExistingSession(Attendance record, LocalDateTime now) {
        Employee shiftContext = resolveShiftContextOrNull(record);
        if (shiftContext == null) {
            return record.isNoShiftAssigned()
                    ? AttendanceInterpretation.noShiftAssigned(now.toLocalDate())
                    : AttendanceInterpretation.legacyUnresolved();
        }
        LocalDate workDateOfNow = shiftDayPolicy.shiftDayOf(shiftContext, now);
        LocalDateTime cutoff = shiftDayPolicy.shiftEndAt(shiftContext, record.getWorkDate());
        LocalDateTime maximumBoundary = shiftDayPolicy.maximumAttendanceBoundary(shiftContext, record.getWorkDate());
        return AttendanceInterpretation.builder()
                .outcome(InterpretationOutcome.RESOLVED)
                .workDate(workDateOfNow)
                .checkoutCutoff(cutoff)
                .maximumBoundary(maximumBoundary)
                .shiftId(shiftContext.getShift().getId())
                .build();
    }

    /**
     * Lateness for a Regularization correction to an ALREADY EXISTING Attendance row (its
     * check-in time is being changed) — resolves against THAT ROW's own snapshotted
     * {@code shiftId} and its own {@code workDate}, never the employee's current Shift. This is
     * the direct fix for "employee reassigned to a different Shift since this historical record's
     * date" silently changing a regularization correction's computed lateness.
     *
     * <p>{@code shiftId == null} is NO_SHIFT_ASSIGNED (never LEGACY_UNRESOLVED) when
     * {@code record.isNoShiftAssigned()} — a valid, current no-Shift row must remain regularizable
     * (approvable) rather than rejected as an unrecoverable legacy row (see that field's own
     * Javadoc). {@code workDate} is the record's own already-known {@code workDate} — there is
     * nothing to derive, same as the RESOLVED branch below.
     */
    @Transactional(readOnly = true)
    public AttendanceInterpretation interpretExistingRecordLateness(Attendance record, LocalDateTime checkInAt) {
        Employee shiftContext = resolveShiftContextOrNull(record);
        if (shiftContext == null) {
            return record.isNoShiftAssigned()
                    ? AttendanceInterpretation.noShiftAssigned(record.getWorkDate())
                    : AttendanceInterpretation.legacyUnresolved();
        }
        return interpretForKnownWorkDate(shiftContext, record.getWorkDate(), checkInAt);
    }

    /**
     * The scheduled shift start/end for an ALREADY EXISTING Attendance row — resolved against
     * THAT ROW's own snapshotted {@code shiftId} and its own {@code workDate}, never the
     * employee's current Shift (same rule as {@link #interpretExistingSession}/
     * {@link #interpretExistingRecordLateness}). Powers the Attendance Log's shift-boundary
     * markers so they keep comparing an old record against the shift it was ACTUALLY worked
     * under, even after the employee is later reassigned or that Shift's timing changes for the
     * future — see {@link ShiftDayPolicy}'s own "Shift Versions" Javadoc section.
     *
     * <p>Reuses {@link ShiftDayPolicy#shiftStartAt}/{@link #shiftEndAt}, so it inherits the same
     * overnight handling (end rolls to {@code workDate + 1} when the effective version's end time
     * is not after its start) — both returned as plain {@link LocalDateTime}s in the record's own
     * historical wall-clock basis, i.e. the same basis {@code checkInAt}/{@code checkOutAt} are
     * already in (see {@code Attendance.timezone}), so a caller can compare them directly without
     * any zone conversion of its own.
     *
     * <p>Also resolves the record's logical WORKDAY window ({@code workdayStart}/{@code
     * workdayEnd}, via {@link ShiftDayPolicy#workdayStartAt}/{@link ShiftDayPolicy#workdayEndAt})
     * alongside the scheduled shift window — the single source of truth the Attendance timeline
     * positions its whole track against (workday-start to workday-end), never calendar midnight
     * to midnight. Same snapshotted-shift/workDate basis as the shift window, so both are always
     * mutually consistent for one record.
     *
     * <p>Returns {@link ScheduledShiftWindow#EMPTY} for a legacy row ({@code shiftId == null}) —
     * never guessed, exactly like {@link #interpretExistingSession}'s own
     * {@link InterpretationOutcome#LEGACY_UNRESOLVED} handling.
     */
    @Transactional(readOnly = true)
    public ScheduledShiftWindow resolveScheduledWindow(Attendance record) {
        Employee shiftContext = resolveShiftContextOrNull(record);
        if (shiftContext == null) {
            return ScheduledShiftWindow.EMPTY;
        }
        LocalDateTime start = shiftDayPolicy.shiftStartAt(shiftContext, record.getWorkDate());
        LocalDateTime end = shiftDayPolicy.shiftEndAt(shiftContext, record.getWorkDate());
        LocalDateTime workdayStart = shiftDayPolicy.workdayStartAt(shiftContext, record.getWorkDate());
        LocalDateTime workdayEnd = shiftDayPolicy.workdayEndAt(shiftContext, record.getWorkDate());
        return new ScheduledShiftWindow(start, end, workdayStart, workdayEnd);
    }

    /**
     * Scheduled shift start/end, plus the logical workday start/end, resolved for one Attendance
     * row — see {@link #resolveScheduledWindow}.
     */
    public record ScheduledShiftWindow(LocalDateTime start, LocalDateTime end,
                                        LocalDateTime workdayStart, LocalDateTime workdayEnd) {
        public static final ScheduledShiftWindow EMPTY = new ScheduledShiftWindow(null, null, null, null);
    }

    /** Plain workday start/end pair — see {@link #resolveWorkdayWindowFor}/{@link #belongsToWorkday}. */
    public record WorkdayWindow(LocalDateTime start, LocalDateTime end) {}

    /**
     * Whether {@code timestamp} belongs to the logical workday {@code workDate} denotes — i.e.
     * {@link ShiftDayPolicy#shiftDayOf} resolves {@code timestamp} to exactly {@code workDate},
     * never a bare "same calendar date" comparison. Used by {@code RegularizationService} to
     * validate a corrected check-in/check-out: a shift running 10:00-19:00 with an 18h maximum
     * workday duration spans workday 04:00 -> 04:00 the NEXT calendar day, so a corrected punch at
     * 12:21 AM or 3:30 AM the next calendar day can still legitimately belong to {@code workDate}'s
     * attendance.
     *
     * <p>Resolves the shift context the exact same existing-vs-new way
     * {@link #interpretExistingRecordLateness}/{@link #interpretForKnownWorkDate} already do
     * elsewhere in this class: {@code existingRecordOrNull}'s own snapshotted {@code shiftId} when
     * an Attendance row already exists for this date (never the employee's current Shift — the
     * exact "reassigned since this record's date" drift this class exists to prevent), or {@code
     * employee}'s CURRENT Shift for a brand-new correction with no prior row to preserve context
     * from.
     *
     * <p>Fails OPEN (returns {@code true}) only for a legacy existing row with no {@code shiftId}
     * snapshot — exactly like every other legacy row this class encounters (see "Legacy rows"
     * above): this is a pre-submission sanity check, not the final authority, and {@link
     * #interpretExistingRecordLateness} already throws its own clear, explicit error for a legacy
     * row at APPROVAL time.
     *
     * <p>NO_SHIFT_ASSIGNED (a brand-new correction, no prior row, and no {@code
     * EmployeeShiftAssignment} effective on {@code workDate} — a brand-new/no-shift employee, or
     * one whose first assignment isn't effective yet) degrades to a plain calendar-date check:
     * there is no Shift to roll an overnight boundary against, so {@code timestamp} belongs to
     * {@code workDate} exactly when its own calendar date is {@code workDate} — mirrors {@link
     * AttendanceInterpretationService}'s own NO_SHIFT_ASSIGNED handling for check-in/check-out
     * elsewhere in this class, never a thrown exception from {@link ShiftDayPolicy#shiftDayOf}.
     */
    @Transactional(readOnly = true)
    public boolean belongsToWorkday(Employee employee, Attendance existingRecordOrNull, LocalDate workDate, LocalDateTime timestamp) {
        if (existingRecordOrNull != null) {
            Employee shiftContext = resolveShiftContextOrNull(existingRecordOrNull);
            if (shiftContext == null) {
                // A valid, current no-Shift row (see Attendance.noShiftAssigned's own Javadoc)
                // degrades to a plain calendar-date check, same as the brand-new-correction branch
                // below — never "fails open" for it the way a genuine legacy row does, since there
                // IS a well-defined answer here (no Shift to roll an overnight boundary against).
                if (existingRecordOrNull.isNoShiftAssigned()) {
                    return timestamp.toLocalDate().equals(workDate);
                }
                return true; // genuine legacy row — fails open, exactly like every other legacy case here
            }
            return shiftDayPolicy.shiftDayOf(shiftContext, timestamp).equals(workDate);
        }
        if (employee == null) {
            return true; // no employee to validate against — fails open, exactly like the legacy-row case above
        }
        if (employeeShiftAssignmentResolver.resolveIfPresent(employee.getUserId(), workDate).isEmpty()) {
            return timestamp.toLocalDate().equals(workDate);
        }
        // Brand-new correction, no prior row — day-aware resolution against the employee's REAL
        // assignment history (never Employee.shift, a best-effort display cache — see that
        // field's own Javadoc), since a reassignment could land exactly on the boundary this
        // examines. See ShiftDayPolicy#shiftDayOf(UUID, LocalDateTime)'s own Javadoc.
        try {
            return shiftDayPolicy.shiftDayOf(employee.getUserId(), timestamp).equals(workDate);
        } catch (NoShiftAssignmentException e) {
            // workDate itself has an effective assignment (just confirmed above), but the
            // REQUESTED timestamp's own calendar date does not — an inconsistent/out-of-range
            // correction (e.g. a requested check-in dated well before the employee's first
            // assignment while attendanceDate itself is on/after it), not a legitimate no-shift
            // case. Rejected as an ordinary "does not belong to this workday" answer — the exact
            // same controlled IllegalArgumentException RegularizationService.resolveTimes already
            // throws for this method's false return. Only this specific "no assignment for that
            // date" exception is caught here — a genuine Shift/ShiftVersion invariant violation
            // (plain IllegalStateException from ShiftVersionResolver#resolve) is NOT this subtype
            // and propagates uncaught, exactly as this class's own Javadoc requires.
            log.warn("belongsToWorkday: employee {} has no resolvable Shift context for the requested "
                            + "timestamp's own calendar date even though workDate={} does — rejecting as "
                            + "an out-of-range correction: {}",
                    employee.getUserId(), workDate, e.getMessage());
            return false;
        }
    }

    /**
     * The workday window (start/end) {@link #belongsToWorkday} validates a timestamp against —
     * exposed separately purely so a caller can phrase a helpful validation message ("must fall
     * between X and Y") instead of a bare rejection. Same shift-context resolution as {@link
     * #belongsToWorkday}; returns {@code null} in the exact same legacy-row case that method fails
     * open for, and the plain calendar-date window (midnight to midnight) in the same
     * NO_SHIFT_ASSIGNED case that method degrades to a calendar-date check for.
     */
    @Transactional(readOnly = true)
    public WorkdayWindow resolveWorkdayWindowFor(Employee employee, Attendance existingRecordOrNull, LocalDate workDate) {
        if (existingRecordOrNull != null) {
            Employee shiftContext = resolveShiftContextOrNull(existingRecordOrNull);
            if (shiftContext == null) {
                // Same no-Shift-assigned vs. genuine-legacy distinction as belongsToWorkday above.
                if (existingRecordOrNull.isNoShiftAssigned()) {
                    return new WorkdayWindow(workDate.atStartOfDay(), workDate.plusDays(1).atStartOfDay());
                }
                return null;
            }
            return new WorkdayWindow(shiftDayPolicy.workdayStartAt(shiftContext, workDate),
                    shiftDayPolicy.workdayEndAt(shiftContext, workDate));
        }
        if (employee == null) {
            return null; // no employee to validate against — same fail-open case as the legacy-row branch above
        }
        if (employeeShiftAssignmentResolver.resolveIfPresent(employee.getUserId(), workDate).isEmpty()) {
            return new WorkdayWindow(workDate.atStartOfDay(), workDate.plusDays(1).atStartOfDay());
        }
        // Brand-new correction, no prior row — same day-aware reasoning as belongsToWorkday above.
        // Same edge case too: workDate has an effective assignment but workdayStartAt's own
        // previous-day lookup, or workdayEndAt's pinForDate, can still throw if some OTHER date
        // this window touches doesn't — degrades to the plain calendar-date window rather than an
        // uncaught 500, exactly like belongsToWorkday's own try/catch.
        try {
            LocalDateTime start = shiftDayPolicy.workdayStartAt(employee.getUserId(), workDate);
            LocalDateTime end = shiftDayPolicy.workdayEndAt(pinForDate(employee.getUserId(), workDate), workDate);
            return new WorkdayWindow(start, end);
        } catch (NoShiftAssignmentException e) {
            // Only "no assignment for that date" is caught here — a genuine Shift/ShiftVersion
            // invariant violation (plain IllegalStateException from ShiftVersionResolver#resolve)
            // is NOT this subtype and propagates uncaught, same rationale as belongsToWorkday above.
            log.warn("resolveWorkdayWindowFor: employee {} has no resolvable Shift context for a date "
                            + "this workday window touches even though workDate={} does — falling back to "
                            + "the plain calendar-date window: {}",
                    employee.getUserId(), workDate, e.getMessage());
            return new WorkdayWindow(workDate.atStartOfDay(), workDate.plusDays(1).atStartOfDay());
        }
    }

    /**
     * Resolves the Shift Assignment effective on {@code date} and builds a minimal pinned
     * {@link Employee} stand-in carrying only that Shift — the same idiom
     * {@link #resolveShiftContextOrNull} uses for a historical row's own snapshot, here sourced
     * from the effective-dated assignment instead. Safe because {@link ShiftDayPolicy} reads
     * nothing else off {@code Employee} (see its own class Javadoc).
     */
    private Employee pinForDate(UUID employeeUserId, LocalDate date) {
        return pin(employeeUserId, employeeShiftAssignmentResolver.resolve(employeeUserId, date));
    }

    /** Builds the same minimal pinned {@link Employee} stand-in as {@link #pinForDate}, given an already-resolved assignment — for a caller that has one on hand and must not re-resolve it. */
    private Employee pin(UUID employeeUserId, EmployeeShiftAssignment assignment) {
        return Employee.builder().userId(employeeUserId).shift(assignment.getShift()).build();
    }

    /**
     * Resolves {@code record.getShiftId()} into a minimal stand-in {@link Employee} carrying ONLY
     * that snapshotted {@link Shift} — {@link ShiftDayPolicy}'s methods read nothing else off
     * {@code Employee} (see its own class Javadoc: {@code shiftOf(employee)} is its only
     * touchpoint), so this is a safe, non-invasive way to make them resolve against the record's
     * own historical Shift instead of the real employee's current one, without modifying
     * {@link ShiftDayPolicy} itself at all. Returns {@code null} for a legacy row
     * ({@code shiftId == null}) — callers must return {@link InterpretationOutcome#LEGACY_UNRESOLVED}
     * rather than substitute the employee's current Shift.
     *
     * <p>If {@code record.getShiftId()} is set but no longer resolves to a real {@link Shift} row,
     * this throws loudly rather than silently degrading — {@code OrgService#deleteShift} rejects
     * deleting any Shift a real Attendance row still references (see V163's FK), so this should be
     * structurally unreachable; a violation here signals data corruption elsewhere, not a case to
     * paper over.
     */
    private Employee resolveShiftContextOrNull(Attendance record) {
        if (record.getShiftId() == null) {
            // Every caller distinguishes NO_SHIFT_ASSIGNED (a valid, current state) from a
            // genuine legacy row via record.isNoShiftAssigned() right after this returns null —
            // logged only for the latter, so an expected, everyday no-Shift row never spams a
            // warning that (accurately) only applies to genuine legacy data.
            if (!record.isNoShiftAssigned()) {
                log.warn("Attendance {} (employee {}, workDate {}) has no Shift snapshot — treating as "
                                + "LEGACY_UNRESOLVED rather than substituting the employee's current Shift",
                        record.getId(), record.getEmployeeUserId(), record.getWorkDate());
            }
            return null;
        }
        Shift snapshotShift = shiftRepository.findById(record.getShiftId())
                .orElseThrow(() -> new IllegalStateException(
                        "Attendance " + record.getId() + " references shift " + record.getShiftId()
                                + " which no longer exists — Shift deletion should be blocked once any "
                                + "Attendance references it (see OrgService#deleteShift); this indicates "
                                + "data corruption, not a case to fall back from."));
        return Employee.builder()
                .userId(record.getEmployeeUserId())
                .shift(snapshotShift)
                .build();
    }
}
