package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.PunchResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.dto.attendance.AttendanceConfigResponse;
import com.nforce.onehr.dto.attendance.AttendanceExceptionResponse;
import com.nforce.onehr.dto.attendance.DailyPunctuality;
import com.nforce.onehr.dto.attendance.PunctualityLeaderboardEntry;
import com.nforce.onehr.dto.attendance.PunctualitySummary;
import com.nforce.onehr.dto.attendance.TeamEffortEntry;
import com.nforce.onehr.dto.attendance.TeamNegligenceResponse;
import com.nforce.onehr.dto.attendance.TeamPunctualityResponse;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendancePunch;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.entity.WebClockInRequest;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Punch-clock attendance: self-service check-in/check-out plus HR/Manager roster views.
 * Regularization (employee-submitted corrections) lives in {@link RegularizationService},
 * which upserts into the same {@link Attendance} rows this service writes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private static final String STATUS_PRESENT = "PRESENT";
    private static final String STATUS_LATE = "LATE";
    private static final String STATUS_HALF_DAY = "HALF_DAY";
    // A session that was never checked out and whose workday/grace window (shiftDayCutover, e.g.
    // 7:00 AM) has since ended — see flagMissingCheckoutIfStale. Deliberately never paired with a
    // fabricated checkOutAt/workedMinutes: the actual check-out time is unknown, so none is
    // guessed. Corrected via the existing Regularization flow, same as any other attendance
    // correction.
    private static final String STATUS_MISSING_CHECKOUT = "MISSING_CHECKOUT";

    private static final int DEFAULT_HISTORY_DAYS = 30;

    private final AttendanceRepository attendanceRepository;
    private final AttendancePunchRepository attendancePunchRepository;
    private final WebClockInRequestRepository webClockInRequestRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final AttendanceProperties props;
    // Shared with WebClockInService so the every-3rd-late-arrival penalty applies identically
    // regardless of which check-in entry point was used — see LatePenaltyService.
    private final LatePenaltyService latePenaltyService;
    private final WorkingDayService workingDayService;

    // ---------------------------------------------------------------- self-service

    @Transactional
    public TodayAttendanceResponse getToday(String actorEmail, String clientTimezone) {
        Employee employee = resolveEmployee(actorEmail);

        // A shift can cross midnight (e.g. 3:30 PM - 12:30 AM) — an open session started
        // yesterday is still the actionable "today" state even once the calendar date has
        // rolled over, so it takes priority over a plain work_date lookup. But an open session
        // whose own workday/grace window has already ended (a forgotten checkout from days ago,
        // not just "yesterday crossing into today") is stale, not today's state — flag it Missing
        // Check-Out (see flagMissingCheckoutIfStale) and fall through to the plain work_date
        // lookup below instead of reporting it as an active session forever.
        Optional<Attendance> open = attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employee.getUserId());

        // An open session's own locked-in zone (from Check-In) decides "now" for it — the
        // viewer's current browser zone only applies once there's no open session to defer to,
        // i.e. for a genuinely fresh "today". See resolveZone's own doc comments.
        LocalDateTime now = LocalDateTime.now(open.isPresent()
                ? resolveZone(open.get(), employee, clientTimezone) : resolveZone(clientTimezone, employee));
        LocalDate today = shiftDayOf(now);

        if (open.isPresent() && !flagMissingCheckoutIfStale(open.get(), now)) {
            Attendance record = open.get();
            return TodayAttendanceResponse.builder()
                    .workDate(record.getWorkDate())
                    .serverNow(now)
                    .canCheckIn(false)
                    .canCheckOut(true)
                    .record(toResponse(record, employee))
                    .build();
        }

        return attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), today)
                .map(record -> TodayAttendanceResponse.builder()
                        .workDate(today)
                        .serverNow(now)
                        // No open session (checked above), so this record is always closed —
                        // another session can still be started (e.g. after a lunch break).
                        .canCheckIn(true)
                        .canCheckOut(false)
                        .record(toResponse(record, employee))
                        .breakUsedMinutes(computeBreakMinutes(employee.getUserId(), record.getId(), today))
                        .breakBudgetMinutes(props.getDailyBreakBudgetMinutes())
                        .build())
                .orElseGet(() -> TodayAttendanceResponse.builder()
                        .workDate(today)
                        .serverNow(now)
                        .canCheckIn(true)
                        .canCheckOut(false)
                        .record(null)
                        .breakUsedMinutes(null)
                        .breakBudgetMinutes(props.getDailyBreakBudgetMinutes())
                        .build());
    }

    /**
     * Sum of the gaps between consecutive closed punch sessions — an open (unclosed) session
     * contributes nothing yet. Spans BOTH punch sources (normal Check-In/Out and Web Check-In/
     * Out): a gap between, say, a Web Check-Out and a later normal Check-In is still a break,
     * so it must not be missed just because the two sessions came from different entry points.
     * See {@link #collectPunches}.
     */
    private int computeBreakMinutes(UUID employeeId, UUID attendanceRecordId, LocalDate workDate) {
        return sumGapMinutes(collectPunches(employeeId, attendanceRecordId, workDate));
    }

    private int sumGapMinutes(List<PunchResponse> punches) {
        int breakMinutes = 0;
        for (int i = 0; i < punches.size() - 1; i++) {
            LocalDateTime gapStart = punches.get(i).getCheckOutAt();
            LocalDateTime gapEnd = punches.get(i + 1).getCheckInAt();
            if (gapStart != null && gapEnd != null) {
                breakMinutes += (int) Duration.between(gapStart, gapEnd).toMinutes();
            }
        }
        return breakMinutes;
    }

    /**
     * Every check-in/check-out session for one employee/day, from BOTH entry points — normal
     * Check-In/Out ({@link AttendancePunch}, source SYSTEM) and Web Check-In/Out
     * ({@link WebClockInRequest}, source WEB_REMOTE — an employee may Web Clock-In/Out more than
     * once per day, see WebClockInService#submit) — merged and sorted chronologically by
     * check-in time. Used for both the punch-history display and the break/gross/effective-hours
     * math, so a session started one way and continued the other still contributes correctly to
     * each, and no session (from either source) is ever double-counted or dropped.
     */
    private List<PunchResponse> collectPunches(UUID employeeId, UUID attendanceRecordId, LocalDate workDate) {
        List<PunchResponse> punches = new ArrayList<>();
        if (attendanceRecordId != null) {
            attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(attendanceRecordId)
                    .forEach(p -> punches.add(PunchResponse.builder()
                            .id(p.getId())
                            .checkInAt(p.getCheckInAt())
                            .checkOutAt(p.getCheckOutAt())
                            .source("SYSTEM")
                            .build()));
        }
        // Not filtered by status (PENDING/APPROVED/REJECTED all included) — a Web Clock-In
        // session is real the moment it's submitted (see WebClockInService#submit's doc
        // comment); HR review only sets a separate approval record, it isn't a gate on whether
        // the session happened or how long it ran.
        webClockInRequestRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(employeeId, workDate)
                .forEach(req -> punches.add(PunchResponse.builder()
                        .id(req.getId())
                        .checkInAt(req.getRequestedCheckIn())
                        .checkOutAt(req.getCheckedOutAt())
                        .source("WEB_REMOTE")
                        .build()));
        punches.sort(Comparator.comparing(PunchResponse::getCheckInAt));
        return punches;
    }

    /** Read-only mirror of AttendanceProperties for the Today's Timings panel — no shiftEnd exists (ONEHR-108 not built). */
    @Transactional(readOnly = true)
    public AttendanceConfigResponse getConfig(String actorEmail) {
        Employee employee = resolveEmployee(actorEmail);
        Shift shift = employee.getShift();
        WeeklyOffPolicy weeklyOffPolicy = employee.getWeeklyOffPolicy();

        return AttendanceConfigResponse.builder()
                .shiftName(shift != null ? shift.getName() : null)
                .shiftStart(resolveShiftStart(employee))
                .shiftEnd(shift != null ? shift.getEndTime() : null)
                .lateGraceMinutes(props.getLateGraceMinutes())
                .halfDayMaxHours(props.getHalfDayMaxHours())
                .fullDayMinHours(props.getFullDayMinHours())
                .dailyBreakBudgetMinutes(props.getDailyBreakBudgetMinutes())
                .weeklyOffDays(weeklyOffPolicy != null
                        ? Arrays.stream(weeklyOffPolicy.getOffDays().split(",")).map(String::trim).toList()
                        : List.of("SATURDAY", "SUNDAY"))
                .build();
    }

    /** The employee's actually-assigned Shift start (ONEHR-108) if present, else the global fallback. */
    private LocalTime resolveShiftStart(Employee employee) {
        Shift shift = employee.getShift();
        return shift != null ? shift.getStartTime() : props.getShiftStart();
    }

    /**
     * Always empty today — see AttendanceExceptionResponse's Javadoc. Passive read only, no
     * detection logic added here.
     */
    @Transactional(readOnly = true)
    public List<AttendanceExceptionResponse> getMyExceptions(String actorEmail, LocalDate from, LocalDate to) {
        Employee employee = resolveEmployee(actorEmail);
        return attendanceExceptionRepository
                .findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                        List.of(employee.getUserId()), from, to)
                .stream()
                .map(e -> AttendanceExceptionResponse.builder()
                        .id(e.getId())
                        .exceptionDate(e.getExceptionDate())
                        .exceptionType(e.getExceptionType())
                        .status(e.getStatus())
                        .minutesLate(e.getMinutesLate())
                        .build())
                .toList();
    }

    @Transactional
    public AttendanceResponse checkIn(String actorEmail, String clientTimezone) {
        Employee employee = resolveEmployee(actorEmail);

        // A shift can cross midnight (e.g. 3:30 PM - 12:30 AM) — an open session from
        // yesterday's work_date must block a fresh check-in exactly like an open session
        // filed under today would, so check for one regardless of date first. But an open
        // session whose own workday/grace window has already ended (a forgotten checkout from
        // days ago) is stale, not a real in-progress day — flag it Missing Check-Out (see
        // flagMissingCheckoutIfStale) instead of letting it block every check-in from now on.
        // Evaluated using the open session's OWN locked-in zone, not this click's browser zone —
        // see resolveZone's doc comments.
        Optional<Attendance> openSession = attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employee.getUserId());
        if (openSession.isPresent()) {
            LocalDateTime openNow = LocalDateTime.now(resolveZone(openSession.get(), employee, clientTimezone));
            if (!flagMissingCheckoutIfStale(openSession.get(), openNow)) {
                throw new IllegalArgumentException("You have already checked in today");
            }
        }

        // A fresh click (or resuming today's own record after the stale-session flag above) —
        // single clock read so the punch time and the work date it's attributed to can never
        // disagree — resolved from THIS click's browser-reported zone, falling back to the
        // employee's configured Location.timezone.
        ZoneId freshZone = resolveZone(clientTimezone, employee);
        LocalDateTime now = LocalDateTime.now(freshZone);
        LocalDate today = shiftDayOf(now);

        Optional<Attendance> existing = attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), today);

        if (existing.isPresent()) {
            // No open session (checked above), so this is always a closed record — resuming
            // after a break (e.g. lunch). The day's original check-in time, late status, and
            // worked-minutes-so-far all stay put; only a new session opens. Reuses the day's
            // ORIGINALLY locked-in zone (from its first check-in), not this click's browser zone,
            // so the whole day's worked-minutes math stays on one consistent clock even if the
            // browser's reported zone has since drifted (e.g. a DST change over a long lunch).
            Attendance record = existing.get();
            LocalDateTime resumeNow = LocalDateTime.now(resolveZone(record, employee, clientTimezone));
            record.setSessionStartedAt(resumeNow);
            record.setCheckOutAt(null);
            Attendance saved = attendanceRepository.save(record);
            openPunch(saved.getId(), resumeNow);
            auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_IN", saved.getId());
            return toResponse(saved, employee);
        }

        // Two independent things, deliberately not derived from one another:
        //
        // 1. isLate (official status, HR/penalty-relevant, grace-aware) — past the grace
        //    deadline (shift start + grace) by even one second, full stop, no forgiveness
        //    beyond the grace window itself. This alone drives `status` and therefore the
        //    3-late-arrivals-a-month penalty. Must NOT be derived from lateByMinutes:
        //    Duration.toMinutes() truncates, so someone 30 seconds past the deadline would
        //    floor to 0 minutes and wrongly read as on-time.
        // 2. lateByMinutes (employee-facing display only) — raw time past shift start, with NO
        //    grace forgiveness: 3:30:01 PM shows as "late by 1s" to the employee even though it
        //    doesn't count as an official late arrival. The grace period is an HR/admin
        //    forgiveness concept for whether a check-in gets penalized — it is not something
        //    that should ever appear in what an employee is told about their own punctuality.
        //
        // Both are measured against the employee's actually-assigned Shift (ONEHR-108) when
        // present — falling back to the global shiftStart would judge lateness against the
        // wrong time of day entirely.
        //
        // Compared as full date-aware instants (shiftStart anchored to `today`, the already-
        // resolved shift-day), NOT bare LocalTime-of-day — a pure LocalTime comparison silently
        // breaks the moment a check-in crosses midnight relative to an overnight shift: e.g. for
        // a 20:30-05:30 shift, a 1:11 AM check-in is genuinely ~4h41m late, but 01:11 as a bare
        // LocalTime is "before" 20:30, so isAfter(shiftStart) would wrongly read false and report
        // 0 minutes late / PRESENT for an obviously-late arrival. Anchoring both sides to `today`
        // fixes this for any shift shape, not just the original 15:30-00:30 case (where this same
        // bug existed but only affected the narrow 00:00-00:30 tail of the shift).
        LocalTime shiftStart = resolveShiftStart(employee);
        LocalDateTime shiftStartAt = LocalDateTime.of(today, shiftStart);
        LocalDateTime deadlineAt = shiftStartAt.plusMinutes(props.getLateGraceMinutes());
        boolean isLate = now.isAfter(deadlineAt);
        int lateByMinutes = now.isAfter(shiftStartAt)
                ? (int) Math.ceil(Duration.between(shiftStartAt, now).getSeconds() / 60.0)
                : 0;

        Attendance record = attendanceRepository.save(Attendance.builder()
                .employeeUserId(employee.getUserId())
                .workDate(today)
                .checkInAt(now)
                .sessionStartedAt(now)
                .status(isLate ? STATUS_LATE : STATUS_PRESENT)
                .lateByMinutes(lateByMinutes)
                .timezone(freshZone.getId())
                .build());
        openPunch(record.getId(), now);
        if (isLate) {
            latePenaltyService.applyIfDue(employee, today);
        }

        auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_IN", record.getId());
        return toResponse(record, employee);
    }

    private void openPunch(UUID attendanceRecordId, LocalDateTime checkInAt) {
        // Defensive: close out any punch(es) still open under this record before opening a new
        // one. Should never happen in the normal flow — checkIn's own open-session guard blocks a
        // second check-in while one is already in progress — but a duplicate/retried request that
        // slips past that guard (e.g. a network-retry race) must not be allowed to leave more
        // than one simultaneously-open punch: that ambiguity is exactly what used to crash
        // checkOut with NonUniqueResultException. Closing any stragglers here, rather than only
        // tolerating them at read time, stops the bad state from accumulating further.
        attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(attendanceRecordId).stream()
                .filter(p -> p.getCheckOutAt() == null)
                .forEach(p -> {
                    p.setCheckOutAt(checkInAt);
                    attendancePunchRepository.save(p);
                });
        attendancePunchRepository.save(AttendancePunch.builder()
                .attendanceRecordId(attendanceRecordId)
                .checkInAt(checkInAt)
                .build());
    }

    @Transactional
    public AttendanceResponse checkOut(String actorEmail, String clientTimezone) {
        Employee employee = resolveEmployee(actorEmail);

        // Looked up by open session, not by today's work_date — a shift that started before
        // midnight (e.g. 3:30 PM - 12:30 AM) is still open under *yesterday's* work_date once
        // the calendar date rolls over.
        Attendance record = attendanceRepository
                .findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employee.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("You have not checked in today"));

        // The session's own zone, locked in at Check-In — NOT this click's browser zone, which
        // may have drifted since (travel, DST) — governs its Check-Out, so worked-minutes math
        // and the grace-window check below stay on one consistent clock for the whole session.
        // clientTimezone only matters as a fallback for a record from before this column existed.
        LocalDateTime now = LocalDateTime.now(resolveZone(record, employee, clientTimezone));

        // Past its own workday/grace window (e.g. someone opens a stale tab and clicks Check Out
        // days later) — there is no legitimate "now" to check out with at this point, so this is
        // flagged Missing Check-Out (same as getToday/checkIn would have already done for this
        // record) rather than silently accepted as a real, very-late checkout. The frontend
        // should never offer this button once getToday reports canCheckOut=false for it, but a
        // stale request must not fabricate a checkout time either way.
        if (flagMissingCheckoutIfStale(record, now)) {
            throw new IllegalArgumentException(
                    "This session is past its check-out window and has been marked as a missing check-out. Please submit a regularization request.");
        }

        // A forgotten checkout can leave a session open for several hours before the employee
        // actually clicks Check-out (e.g. checking out at 3 AM for a shift that ended at
        // 12:30 AM); count worked time only up to this shift's own natural end (not the late
        // click's real clock time), so it can never inflate into something like "27h 8m" for
        // what is supposed to be a single shift/day. Only reachable here at all within the grace
        // window above — a click that arrives after the grace window is now rejected outright.
        LocalDateTime cutoff = shiftEndCutoff(employee, record.getWorkDate());
        LocalDateTime effectiveCheckOut = now.isAfter(cutoff) ? cutoff : now;

        String before = auditSnapshot.toJson(Map.of(
                "checkOutAt", "null", "workedMinutes", record.getWorkedMinutes() != null ? record.getWorkedMinutes() : 0));
        Attendance saved = closeSession(record, effectiveCheckOut);
        String after = auditSnapshot.toJson(Map.of(
                "checkOutAt", effectiveCheckOut.toString(), "workedMinutes", saved.getWorkedMinutes(), "status", saved.getStatus()));
        auditService.log(employee.getUserId(), "ATTENDANCE_CHECKED_OUT", saved.getId(), before, after);
        return toResponse(saved, employee);
    }

    /**
     * Closes an open attendance record as of {@code effectiveCheckOut} — accumulates worked
     * minutes, sets the resulting status, and closes the matching open punch — exactly what an
     * explicit {@link #checkOut} does. Shared with {@link #autoCloseIfStale}, which closes a
     * session nobody ever came back to click Check-out on: both close the same way, just
     * triggered differently (an explicit click vs. the shift's own end having already passed).
     */
    private Attendance closeSession(Attendance record, LocalDateTime effectiveCheckOut) {
        // Sessions accumulate: only this session's minutes are added to whatever was
        // already worked earlier today, so a lunch break isn't counted as worked time.
        // Rounded to the nearest minute, not truncated: Duration.toMinutes() floors, so several
        // short sessions (e.g. under a minute each) would each independently floor to 0 and the
        // total would silently lose real worked time instead of just losing sub-minute
        // precision on the total once.
        LocalDateTime sessionStart = record.getSessionStartedAt() != null
                ? record.getSessionStartedAt() : record.getCheckInAt();
        long sessionSeconds = Math.max(0, Duration.between(sessionStart, effectiveCheckOut).getSeconds());
        int sessionMinutes = (int) Math.round(sessionSeconds / 60.0);
        int workedMinutes = (record.getWorkedMinutes() != null ? record.getWorkedMinutes() : 0) + sessionMinutes;
        record.setCheckOutAt(effectiveCheckOut);
        record.setWorkedMinutes(workedMinutes);

        // A short day overrides LATE — the shortfall is the more significant fact for payroll.
        if (workedMinutes < props.getHalfDayMaxHours() * 60) {
            record.setStatus(STATUS_HALF_DAY);
        } else {
            record.setStatus(record.getLateByMinutes() > 0 ? STATUS_LATE : STATUS_PRESENT);
        }

        Attendance saved = attendanceRepository.save(record);
        // findFirstBy...OrderByCheckInAtDesc, not a plain findBy: if more than one punch is ever
        // left open under this record (a data slip, or two near-simultaneous check-ins racing
        // past the open-session guard above), a plain findBy throws NonUniqueResultException and
        // crashes the checkout instead of just closing the most recently opened session.
        attendancePunchRepository.findFirstByAttendanceRecordIdAndCheckOutAtIsNullOrderByCheckInAtDesc(saved.getId())
                .ifPresent(punch -> {
                    punch.setCheckOutAt(effectiveCheckOut);
                    attendancePunchRepository.save(punch);
                });
        return saved;
    }

    /**
     * A session left open past its own workday/grace window (shiftDayCutover, e.g. 7:00 AM the
     * next calendar day — see {@link #shiftDayOf}) — the employee forgot to check out and never
     * came back to click it — must not go on blocking fresh check-ins ({@link #checkIn}) or
     * showing as "still checked in" / offering a Check Out button forever ({@link #getToday}), no
     * matter how many calendar days have since passed. Flags it {@link #STATUS_MISSING_CHECKOUT}
     * right then — deliberately WITHOUT fabricating a checkOutAt or computing workedMinutes; the
     * real check-out time is unknown, so none is guessed. (Employee/HR/Manager can still correct
     * it via the existing Regularization flow.) Returns whether the record is — now or
     * already — flagged, so the caller can fall through to treating this employee as having no
     * open session.
     *
     * <p>An open session still within its own workday/grace window (e.g. checked in at 11 PM,
     * now 2 AM — the 3:30 PM - 12:30 AM shift has ended but the 7 AM cutover hasn't) is left
     * untouched — this only fires once the grace window has genuinely ended, never for a session
     * legitimately still correctable (a late but real Check-Out click — see {@link #checkOut}'s
     * own {@link #shiftEndCutoff} cap) or still in progress across midnight.
     */
    private boolean flagMissingCheckoutIfStale(Attendance record, LocalDateTime now) {
        if (record.getCheckOutAt() != null) return false;
        if (STATUS_MISSING_CHECKOUT.equals(record.getStatus())) return true;
        if (!shiftDayOf(now).isAfter(record.getWorkDate())) return false;

        String before = auditSnapshot.toJson(Map.of("status", record.getStatus()));
        record.setStatus(STATUS_MISSING_CHECKOUT);
        Attendance saved = attendanceRepository.save(record);
        String after = auditSnapshot.toJson(Map.of("status", STATUS_MISSING_CHECKOUT));
        auditService.log(record.getEmployeeUserId(), "ATTENDANCE_MISSING_CHECKOUT", saved.getId(), before, after);
        return true;
    }

    /**
     * Org-wide sweep for {@link StaleAttendanceSweeper}: flags every currently-open attendance
     * record whose own workday/grace window has already ended as Missing Check-Out, regardless of
     * whether that employee ever opens the app again to trigger {@link #flagMissingCheckoutIfStale}
     * themselves via {@link #getToday}/{@link #checkIn}. Without this, an employee who forgets to
     * check out and simply doesn't come back (a resignation, an extended leave, a forgotten
     * account) would leave that session open — and visibly "still checked in" to HR —
     * indefinitely.
     */
    @Transactional
    public void flagAllStaleOpenSessionsAsMissingCheckout() {
        List<Attendance> open = attendanceRepository.findByCheckOutAtIsNull();
        int flagged = 0;
        for (Attendance record : open) {
            Optional<Employee> employee = employeeRepository.findById(record.getEmployeeUserId());
            if (employee.isPresent()
                    && flagMissingCheckoutIfStale(record, LocalDateTime.now(resolveZone(record, employee.get())))) {
                flagged++;
            }
        }
        if (flagged > 0) {
            log.info("flagAllStaleOpenSessionsAsMissingCheckout: flagged {} of {} open session(s) as Missing Check-Out", flagged, open.size());
        }
    }

    /**
     * Natural end of the shift covering workDate, crossing into the next calendar day when the
     * configured end time is earlier than the start (e.g. 3:30 PM - 12:30 AM). Worked-minutes
     * calculations are bounded to this so a checkout that arrives a day (or more) late — a
     * forgotten session — can't be counted as if the employee worked continuously the whole
     * time in between.
     */
    private LocalDateTime shiftEndCutoff(Employee employee, LocalDate workDate) {
        LocalTime shiftStart = resolveShiftStart(employee);
        Shift shift = employee.getShift();
        LocalTime shiftEnd = shift != null ? shift.getEndTime() : null;
        if (shiftEnd == null) {
            // No shift end configured — fall back to a generous full day from shift start
            // rather than leaving worked-hours completely unbounded.
            return LocalDateTime.of(workDate, shiftStart).plusHours(24);
        }
        LocalDate endDate = !shiftEnd.isAfter(shiftStart) ? workDate.plusDays(1) : workDate;
        return LocalDateTime.of(endDate, shiftEnd);
    }

    /**
     * Every check-in/check-out session for a single day — e.g. to show a lunch-break gap.
     * Includes both normal Check-In/Out and Web Check-In/Out sessions, merged chronologically —
     * see {@link #collectPunches}.
     */
    @Transactional(readOnly = true)
    public List<PunchResponse> getPunches(String actorEmail, LocalDate date) {
        Employee employee = resolveEmployee(actorEmail);
        UUID attendanceRecordId = attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), date)
                .map(Attendance::getId)
                .orElse(null);
        return collectPunches(employee.getUserId(), attendanceRecordId, date);
    }

    @Transactional(readOnly = true)
    public List<AttendanceResponse> getMyHistory(String actorEmail, LocalDate from, LocalDate to) {
        Employee employee = resolveEmployee(actorEmail);
        return historyFor(employee, from, to);
    }

    /**
     * The caller's own punch for a single date, if any — backs the regularization request
     * form's auto-fill (Attendance Regularization spec scenarios 1/2: prefill whichever side
     * of the punch already exists so only the missing one needs to be entered). Null if the
     * employee never punched that day.
     */
    @Transactional(readOnly = true)
    public AttendanceResponse getPunchForDate(String actorEmail, LocalDate date) {
        Employee employee = resolveEmployee(actorEmail);
        return attendanceRepository.findByEmployeeUserIdAndWorkDate(employee.getUserId(), date)
                .map(record -> toResponse(record, employee))
                .orElse(null);
    }

    // ---------------------------------------------------------------- HR / Manager views

    /** Full day roster for HR — one row per active employee, punched or not. */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getDayForAll(LocalDate date) {
        LocalDate day = date != null ? date : shiftDayOf(now());
        List<Employee> employees = employeeRepository.findAllWithDetails();
        return joinRoster(employees, attendanceRepository.findByWorkDate(day), day);
    }

    /** Day roster limited to the caller's current direct reports. */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getDayForMyTeam(String managerEmail, LocalDate date) {
        LocalDate day = date != null ? date : shiftDayOf(now());
        Employee manager = resolveEmployee(managerEmail);

        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }

        List<Employee> reports = employeeRepository.findAllById(reportIds).stream()
                .filter(e -> e.getUser() != null && e.getUser().getDeletedAt() == null)
                .toList();
        List<Attendance> records =
                attendanceRepository.findByWorkDateAndEmployeeUserIdIn(day, reportIds);
        return joinRoster(reports, records, day);
    }

    /**
     * Attendance rows for the caller's current direct reports across a date range — backs the
     * My Team calendar. Unlike {@link #getDayForMyTeam}, this returns only rows that actually
     * exist (no synthetic per-day placeholders for a whole team/range); the caller infers a
     * "missing attendance" day from the absence of a row on a working day.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getMonthForMyTeam(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Employee> byId = employeeRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        return attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to).stream()
                .map(r -> toResponse(r, byId.get(r.getEmployeeUserId())))
                .toList();
    }

    /**
     * Day roster limited to the caller's current peers (same-manager siblings) — backs the
     * employee-facing My Team: Peers view (ONEHR-73). Mirrors {@link #getDayForMyTeam} exactly,
     * swapping direct-report resolution for peer resolution.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getDayForPeers(String employeeEmail, LocalDate date) {
        LocalDate day = date != null ? date : shiftDayOf(now());
        Employee self = resolveEmployee(employeeEmail);

        // "Project Team" = every employee (including the caller) who currently reports to the
        // same manager — empty if the caller has no manager assigned, since there's no team to
        // belong to in that case.
        if (managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(self.getUserId()).isEmpty()) {
            return List.of();
        }
        // findCurrentPeerIds already includes the caller themself (see its own doc comment) — no
        // need to add self.getUserId() again here.
        List<UUID> teamIds = managerHistoryRepository.findCurrentPeerIds(self.getUserId());

        List<Employee> team = employeeRepository.findAllById(teamIds).stream()
                .filter(e -> e.getUser() != null && e.getUser().getDeletedAt() == null)
                .toList();
        List<Attendance> records =
                attendanceRepository.findByWorkDateAndEmployeeUserIdIn(day, teamIds);
        return joinRoster(team, records, day);
    }

    /** Project Team attendance across a date range — backs the Peers view calendar. Mirrors {@link #getMonthForMyTeam}. */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getMonthForPeers(String employeeEmail, LocalDate from, LocalDate to) {
        Employee self = resolveEmployee(employeeEmail);
        if (managerHistoryRepository.findByEmployeeUserIdAndEffectiveToIsNull(self.getUserId()).isEmpty()) {
            return List.of();
        }
        // findCurrentPeerIds already includes the caller themself (see its own doc comment) — no
        // need to add self.getUserId() again here.
        List<UUID> teamIds = managerHistoryRepository.findCurrentPeerIds(self.getUserId());

        Map<UUID, Employee> byId = employeeRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        return attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(teamIds, from, to).stream()
                .map(r -> toResponse(r, byId.get(r.getEmployeeUserId())))
                .toList();
    }

    /**
     * Drill-down history for a single employee. HR and Super Admin may view anyone; a Manager
     * may only view their own current direct reports.
     */
    @Transactional(readOnly = true)
    public List<AttendanceResponse> getEmployeeHistory(UUID employeeUserId, LocalDate from, LocalDate to,
                                                       String actorEmail, boolean restrictToDirectReports) {
        if (restrictToDirectReports) {
            Employee manager = resolveEmployee(actorEmail);
            if (!managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId())
                    .contains(employeeUserId)) {
                throw new AccessDeniedException("You can only view attendance for your direct reports");
            }
        }

        Employee employee = employeeRepository.findById(employeeUserId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));
        return historyFor(employee, from, to);
    }

    // ---------------------------------------------------------------- Team Effort / Negligence (ONEHR-106/107)

    private static final int EXPECTED_HOURS_PER_WORKDAY = 8;

    /** Avg. Work Hours Leaderboard — ranked desc by avg hrs/day over the range (ONEHR-106). */
    @Transactional(readOnly = true)
    public List<TeamEffortEntry> getTeamEffort(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }

        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to);
        Map<UUID, Employee> byId = employeeRepository.findAllByIdWithScheduleDetails(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        // Working-days-per-employee (joining date, weekly off, holidays, approved leave) —
        // NOT a flat weekday count: two direct reports can have different expected hours in the
        // same range (e.g. one joined mid-range, or is on a non-Sat/Sun weekly-off policy).
        Map<UUID, WorkingDaySchedule> schedules =
                workingDayService.computeExpectedWorkingDaysBulk(new ArrayList<>(byId.values()), from, to);

        return groupByEmployee(records).entrySet().stream()
                .filter(e -> e.getValue().activeDays > 0)
                .map(e -> {
                    TeamStat stat = e.getValue();
                    Employee employee = byId.get(e.getKey());
                    WorkingDaySchedule schedule = schedules.get(e.getKey());
                    double expectedHours = (schedule != null ? schedule.getExpectedWorkingDays() : 0) * EXPECTED_HOURS_PER_WORKDAY;
                    return TeamEffortEntry.builder()
                            .employeeUserId(e.getKey())
                            .fullName(employee != null ? employee.getFullName() : null)
                            .designationName(designationOf(employee))
                            .avgHoursPerDay(round1(stat.totalWorkedMinutes / 60.0 / stat.activeDays))
                            .hoursWorked(round1(stat.totalWorkedMinutes / 60.0))
                            .expectedHours(expectedHours)
                            .activeDays(stat.activeDays)
                            .build();
                })
                .sorted(Comparator.comparingDouble(TeamEffortEntry::getAvgHoursPerDay).reversed())
                .toList();
    }

    /**
     * Team Punctuality / On-Time Leaderboard — "on time" is defined solely as
     * {@code attendance.status == PRESENT}; LATE, HALF_DAY, and no record at all (ABSENT) never
     * count. Ranked desc by percentage, ties left in whatever order the underlying stream
     * produces (no invented secondary tie-break). Direct reports with zero expected working
     * days in the range are excluded from the leaderboard entirely, not shown as 0%.
     */
    @Transactional(readOnly = true)
    public TeamPunctualityResponse getTeamPunctuality(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return TeamPunctualityResponse.builder()
                    .leaderboard(List.of()).daily(List.of())
                    .summary(PunctualitySummary.builder().averageEmployeesOnTime(0).minimumEmployeesOnTime(0).maximumEmployeesOnTime(0).build())
                    .build();
        }

        List<Employee> reports = employeeRepository.findAllByIdWithScheduleDetails(reportIds);
        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to);
        Map<UUID, WorkingDaySchedule> schedules = workingDayService.computeExpectedWorkingDaysBulk(reports, from, to);

        Map<UUID, Set<LocalDate>> onTimeDatesByEmployee = records.stream()
                .filter(r -> STATUS_PRESENT.equals(r.getStatus()))
                .collect(Collectors.groupingBy(Attendance::getEmployeeUserId,
                        Collectors.mapping(Attendance::getWorkDate, Collectors.toSet())));

        List<PunctualityLeaderboardEntry> leaderboard = new ArrayList<>();
        // Union of every direct report's working dates — a date only belongs on the daily chart
        // if it was a working day for at least one of them.
        Map<LocalDate, Integer> onTimeCountByDate = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            onTimeCountByDate.put(d, 0);
        }
        Set<LocalDate> anyWorkingDate = new HashSet<>();

        for (Employee employee : reports) {
            WorkingDaySchedule schedule = schedules.get(employee.getUserId());
            if (schedule == null || schedule.getExpectedWorkingDays() == 0) {
                continue;
            }
            Set<LocalDate> onTimeDates = onTimeDatesByEmployee.getOrDefault(employee.getUserId(), Set.of());
            int onTimeDays = 0;
            for (LocalDate workingDate : schedule.getWorkingDates()) {
                anyWorkingDate.add(workingDate);
                if (onTimeDates.contains(workingDate)) {
                    onTimeDays++;
                    onTimeCountByDate.merge(workingDate, 1, Integer::sum);
                }
            }
            leaderboard.add(PunctualityLeaderboardEntry.builder()
                    .employeeUserId(employee.getUserId())
                    .fullName(employee.getFullName())
                    .designationName(designationOf(employee))
                    .onTimeDays(onTimeDays)
                    .expectedWorkingDays(schedule.getExpectedWorkingDays())
                    .percentage(round1(onTimeDays * 100.0 / schedule.getExpectedWorkingDays()))
                    .build());
        }
        leaderboard.sort(Comparator.comparingDouble(PunctualityLeaderboardEntry::getPercentage).reversed());

        List<DailyPunctuality> daily = onTimeCountByDate.entrySet().stream()
                .filter(e -> anyWorkingDate.contains(e.getKey()))
                .map(e -> DailyPunctuality.builder().date(e.getKey()).employeesOnTime(e.getValue()).build())
                .toList();

        List<Integer> applicableCounts = daily.stream().map(DailyPunctuality::getEmployeesOnTime).toList();
        PunctualitySummary summary = applicableCounts.isEmpty()
                ? PunctualitySummary.builder().averageEmployeesOnTime(0).minimumEmployeesOnTime(0).maximumEmployeesOnTime(0).build()
                : PunctualitySummary.builder()
                        .averageEmployeesOnTime(round1(applicableCounts.stream().mapToInt(Integer::intValue).average().orElse(0)))
                        .minimumEmployeesOnTime(applicableCounts.stream().min(Integer::compareTo).orElse(0))
                        .maximumEmployeesOnTime(applicableCounts.stream().max(Integer::compareTo).orElse(0))
                        .build();

        return TeamPunctualityResponse.builder().leaderboard(leaderboard).daily(daily).summary(summary).build();
    }

    /** The three Negligence panels: Late Arrivals, Least Hours Worked, Frequent Breaks (ONEHR-107). */
    @Transactional(readOnly = true)
    public TeamNegligenceResponse getTeamNegligence(String managerEmail, LocalDate from, LocalDate to) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return TeamNegligenceResponse.builder()
                    .lateArrivals(List.of()).dailyLateCounts(List.of())
                    .leastHoursWorked(List.of()).hoursHistogram(List.of())
                    .frequentBreaks(List.of()).build();
        }

        List<Attendance> records = attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(reportIds, from, to);
        Map<UUID, Employee> byId = employeeRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));
        Map<UUID, TeamStat> statsByEmployee = groupByEmployee(records);

        List<TeamNegligenceResponse.LateArrivalEntry> lateArrivals = statsByEmployee.entrySet().stream()
                .filter(e -> e.getValue().activeDays > 0)
                .map(e -> {
                    TeamStat stat = e.getValue();
                    Employee employee = byId.get(e.getKey());
                    return TeamNegligenceResponse.LateArrivalEntry.builder()
                            .employeeUserId(e.getKey())
                            .fullName(employee != null ? employee.getFullName() : null)
                            .designationName(designationOf(employee))
                            .lateDays(stat.lateDays)
                            .activeDays(stat.activeDays)
                            .latePct(round1(stat.lateDays * 100.0 / stat.activeDays))
                            .build();
                })
                .sorted(Comparator.comparingDouble(TeamNegligenceResponse.LateArrivalEntry::getLatePct).reversed())
                .toList();

        Map<LocalDate, Long> lateByDate = records.stream()
                .filter(r -> STATUS_LATE.equals(r.getStatus()))
                .collect(Collectors.groupingBy(Attendance::getWorkDate, Collectors.counting()));
        List<TeamNegligenceResponse.DailyCount> dailyLateCounts = from.datesUntil(to.plusDays(1))
                .map(d -> TeamNegligenceResponse.DailyCount.builder().date(d).count(lateByDate.getOrDefault(d, 0L)).build())
                .toList();

        List<TeamNegligenceResponse.LeastHoursEntry> leastHoursWorked = statsByEmployee.entrySet().stream()
                .filter(e -> e.getValue().activeDays > 0)
                .map(e -> {
                    TeamStat stat = e.getValue();
                    Employee employee = byId.get(e.getKey());
                    return TeamNegligenceResponse.LeastHoursEntry.builder()
                            .employeeUserId(e.getKey())
                            .fullName(employee != null ? employee.getFullName() : null)
                            .designationName(designationOf(employee))
                            .avgHoursPerDay(round1(stat.totalWorkedMinutes / 60.0 / stat.activeDays))
                            .hoursWorked(round1(stat.totalWorkedMinutes / 60.0))
                            .build();
                })
                .sorted(Comparator.comparingDouble(TeamNegligenceResponse.LeastHoursEntry::getAvgHoursPerDay))
                .toList();

        FrequentBreaksResult breaks = computeFrequentBreaks(records, byId, from, to);

        return TeamNegligenceResponse.builder()
                .lateArrivals(lateArrivals)
                .dailyLateCounts(dailyLateCounts)
                .leastHoursWorked(leastHoursWorked)
                .hoursHistogram(buildHoursHistogram(leastHoursWorked))
                .frequentBreaks(breaks.entries)
                .breaksTrend(breaks.trend)
                .build();
    }

    /** Per-employee accumulator for the range — active/late days and total worked minutes. */
    private static final class TeamStat {
        int activeDays;
        int lateDays;
        int totalWorkedMinutes;
    }

    private Map<UUID, TeamStat> groupByEmployee(List<Attendance> records) {
        Map<UUID, TeamStat> stats = new HashMap<>();
        for (Attendance r : records) {
            if (r.getCheckInAt() == null) {
                continue; // no punch that day — not an "active" day for averaging purposes
            }
            TeamStat stat = stats.computeIfAbsent(r.getEmployeeUserId(), k -> new TeamStat());
            stat.activeDays++;
            if (STATUS_LATE.equals(r.getStatus())) {
                stat.lateDays++;
            }
            stat.totalWorkedMinutes += r.getWorkedMinutes() != null ? r.getWorkedMinutes() : 0;
        }
        return stats;
    }

    private List<TeamNegligenceResponse.HoursBucket> buildHoursHistogram(
            List<TeamNegligenceResponse.LeastHoursEntry> entries) {
        String[] labels = {"< 4 hours", "4 - 5 hours", "5 - 6 hours", "6 - 7 hours", "7 - 8 hours", ">= 8 hours"};
        int[] counts = new int[labels.length];
        for (TeamNegligenceResponse.LeastHoursEntry e : entries) {
            double h = e.getAvgHoursPerDay();
            int idx = h < 4 ? 0 : h < 5 ? 1 : h < 6 ? 2 : h < 7 ? 3 : h < 8 ? 4 : 5;
            counts[idx]++;
        }
        int total = entries.size();
        List<TeamNegligenceResponse.HoursBucket> buckets = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            double pct = total == 0 ? 0 : counts[i] * 100.0 / total;
            buckets.add(TeamNegligenceResponse.HoursBucket.builder()
                    .label(labels[i]).count(counts[i]).pct(round1(pct)).build());
        }
        return buckets;
    }

    /** Per-employee accumulator for break count/minutes while walking punch sessions. */
    private static final class BreakStat {
        int count;
        long minutes;
    }

    /** Per-day accumulator for the trend line: total breaks and how many employees punched that day. */
    private static final class DayBreakTotals {
        int totalBreaks;
        int employeesWithSessions;
    }

    private static final class FrequentBreaksResult {
        final List<TeamNegligenceResponse.FrequentBreaksEntry> entries;
        final List<TeamNegligenceResponse.DailyAverage> trend;

        FrequentBreaksResult(List<TeamNegligenceResponse.FrequentBreaksEntry> entries,
                              List<TeamNegligenceResponse.DailyAverage> trend) {
            this.entries = entries;
            this.trend = trend;
        }
    }

    private FrequentBreaksResult computeFrequentBreaks(
            List<Attendance> records, Map<UUID, Employee> byId, LocalDate from, LocalDate to) {
        if (records.isEmpty()) {
            return new FrequentBreaksResult(List.of(), List.of());
        }
        Map<UUID, Attendance> recordById = records.stream()
                .collect(Collectors.toMap(Attendance::getId, Function.identity()));
        List<AttendancePunch> punches =
                attendancePunchRepository.findByAttendanceRecordIdInOrderByCheckInAtAsc(recordById.keySet());
        Map<UUID, List<AttendancePunch>> byRecord = punches.stream()
                .collect(Collectors.groupingBy(AttendancePunch::getAttendanceRecordId));

        Map<UUID, BreakStat> statByEmployee = new HashMap<>();
        Map<UUID, Integer> activeDaysWithPunches = new HashMap<>();
        Map<LocalDate, DayBreakTotals> dailyTotals = new HashMap<>();

        for (Map.Entry<UUID, List<AttendancePunch>> entry : byRecord.entrySet()) {
            List<AttendancePunch> sessions = entry.getValue(); // already ordered by checkInAt asc
            Attendance record = recordById.get(entry.getKey());
            if (sessions.isEmpty() || record == null) {
                continue;
            }
            UUID employeeUserId = record.getEmployeeUserId();
            activeDaysWithPunches.merge(employeeUserId, 1, Integer::sum);
            BreakStat stat = statByEmployee.computeIfAbsent(employeeUserId, k -> new BreakStat());
            DayBreakTotals dayTotals = dailyTotals.computeIfAbsent(record.getWorkDate(), k -> new DayBreakTotals());
            dayTotals.employeesWithSessions++;

            // sessions.size() - 1 gaps = the day's breaks; the first session isn't a break.
            for (int i = 0; i < sessions.size() - 1; i++) {
                AttendancePunch current = sessions.get(i);
                AttendancePunch next = sessions.get(i + 1);
                stat.count++;
                dayTotals.totalBreaks++;
                if (current.getCheckOutAt() != null) {
                    stat.minutes += Duration.between(current.getCheckOutAt(), next.getCheckInAt()).toMinutes();
                }
            }
        }

        List<TeamNegligenceResponse.FrequentBreaksEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, BreakStat> entry : statByEmployee.entrySet()) {
            if (entry.getValue().count == 0) {
                continue; // no breaks in range — excluded entirely, not shown as 0 (AC #4)
            }
            Employee employee = byId.get(entry.getKey());
            int activeDays = activeDaysWithPunches.getOrDefault(entry.getKey(), 1);
            entries.add(TeamNegligenceResponse.FrequentBreaksEntry.builder()
                    .employeeUserId(entry.getKey())
                    .fullName(employee != null ? employee.getFullName() : null)
                    .designationName(designationOf(employee))
                    .totalBreakHours(round1(entry.getValue().minutes / 60.0))
                    .totalBreakCount(entry.getValue().count)
                    .avgBreaksPerDay(round1((double) entry.getValue().count / activeDays))
                    .build());
        }
        entries.sort(Comparator.comparingDouble(TeamNegligenceResponse.FrequentBreaksEntry::getTotalBreakHours).reversed());

        List<TeamNegligenceResponse.DailyAverage> trend = from.datesUntil(to.plusDays(1))
                .map(d -> {
                    DayBreakTotals t = dailyTotals.get(d);
                    double avg = (t == null || t.employeesWithSessions == 0)
                            ? 0 : (double) t.totalBreaks / t.employeesWithSessions;
                    return TeamNegligenceResponse.DailyAverage.builder().date(d).avgBreaks(round1(avg)).build();
                })
                .toList();

        return new FrequentBreaksResult(entries, trend);
    }

    private String designationOf(Employee employee) {
        return employee != null && employee.getDesignation() != null ? employee.getDesignation().getTitle() : null;
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    // ---------------------------------------------------------------- internals

    /**
     * Clock for HR/Manager roster views (getDayForAll/getDayForMyTeam/getDayForPeers) that span
     * many employees at once — those stay on the single global business zone deliberately: with
     * employees potentially in different timezones there's no single unambiguous "whose clock"
     * answer for an aggregate view, so this is intentionally NOT per-employee. Reads the
     * configured business zone rather than the JVM default so "today" is identical in local dev
     * and on Railway (which runs UTC).
     */
    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of(props.getZone()));
    }

    /**
     * Clock for a specific employee's own self-service actions and history — check-in/out,
     * "today" status, worked-hours/late-arrival math, and shift-day attribution are all computed
     * in THIS employee's own configured location's timezone, not the single global business
     * zone. Falls back to the global zone when the employee has no location assigned, or their
     * location has no timezone configured — so behavior is unchanged for any employee HR hasn't
     * explicitly set a location timezone for.
     */
    private LocalDateTime now(Employee employee) {
        return LocalDateTime.now(zoneIdFor(employee));
    }

    private ZoneId zoneIdFor(Employee employee) {
        String timezone = employee.getLocation() != null ? employee.getLocation().getTimezone() : null;
        return (timezone != null && !timezone.isBlank()) ? ZoneId.of(timezone) : ZoneId.of(props.getZone());
    }

    /**
     * Parses an IANA zone id (e.g. from the browser's {@code Intl.DateTimeFormat()
     * .resolvedOptions().timeZone}), or null if it's missing/blank/not a real zone — callers
     * fall back to {@link #zoneIdFor} rather than fail the request over a malformed value.
     */
    private ZoneId parseZone(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        try {
            return ZoneId.of(candidate.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Zone for a fresh Check-In/Web Clock-In click: the browser-reported zone if it's present
     * and valid, else the employee's configured Location.timezone (then the global business
     * zone) — see {@link #zoneIdFor}. This is the value that gets locked into {@link
     * Attendance#getTimezone()} for the rest of that session's lifetime.
     */
    private ZoneId resolveZone(String clientTimezone, Employee employee) {
        ZoneId fromClient = parseZone(clientTimezone);
        return fromClient != null ? fromClient : zoneIdFor(employee);
    }

    /**
     * Zone for an EXISTING session — its own {@link Attendance#getTimezone()}, locked in at
     * Check-In, governs Check-Out/grace-window/worked-minutes math for as long as it's open, so
     * a browser reporting a different zone later (travel, DST) can't shift that session's
     * shift-day or inflate/shrink its worked hours. {@code clientTimezoneFallback} (and then
     * Location.timezone) only apply for a record from before this column existed.
     */
    private ZoneId resolveZone(Attendance record, Employee employee, String clientTimezoneFallback) {
        ZoneId stored = parseZone(record.getTimezone());
        return stored != null ? stored : resolveZone(clientTimezoneFallback, employee);
    }

    private ZoneId resolveZone(Attendance record, Employee employee) {
        return resolveZone(record, employee, null);
    }

    /**
     * The shift-day (work_date) a given instant belongs to. The shift runs 3:30 PM - 12:30 AM,
     * crossing midnight — anything from midnight up to shiftDayCutover (7:00 AM by default)
     * still belongs to the PREVIOUS calendar date's shift-day, not the new one. E.g. a fresh
     * check-in at 2:00 AM on the 13th is attributed to the 12th; the same check-in at 7:01 AM
     * is attributed to the 13th.
     * Only relevant when there's no already-open session to resume: an open session is always
     * found by findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc regardless of
     * calendar date (see checkIn/checkOut/getToday), so this only decides the work_date for a
     * genuinely fresh punch, or for "today" defaults in views with no open session in play.
     */
    private LocalDate shiftDayOf(LocalDateTime dateTime) {
        return dateTime.toLocalTime().isBefore(props.getShiftDayCutover())
                ? dateTime.toLocalDate().minusDays(1)
                : dateTime.toLocalDate();
    }

    private Employee resolveEmployee(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }

    private List<AttendanceResponse> historyFor(Employee employee, LocalDate from, LocalDate to) {
        // Scoped to one specific employee (never an aggregate/roster view), so their own
        // timezone unambiguously answers "what does 'today' mean" for defaulting the range end.
        LocalDate end = to != null ? to : shiftDayOf(now(employee));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_HISTORY_DAYS);
        return attendanceRepository
                .findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(
                        employee.getUserId(), start, end)
                .stream()
                .map(record -> toResponse(record, employee))
                .toList();
    }

    /** Left-joins a day's records onto an employee list so non-punchers still appear as a row. */
    private List<AttendanceResponse> joinRoster(List<Employee> employees, List<Attendance> records,
                                                LocalDate day) {
        Map<UUID, Attendance> byEmployee = records.stream()
                .collect(Collectors.toMap(Attendance::getEmployeeUserId, Function.identity()));

        List<AttendanceResponse> rows = new ArrayList<>(employees.size());
        for (Employee employee : employees) {
            Attendance record = byEmployee.get(employee.getUserId());
            rows.add(record != null
                    ? toResponse(record, employee)
                    : AttendanceResponse.builder()
                            .employeeUserId(employee.getUserId())
                            .employeeCode(employee.getEmployeeCode())
                            .fullName(employee.getFullName())
                            .workDate(day)
                            .workMode(employee.getWorkMode())
                            .build());
        }
        rows.sort(Comparator.comparing(AttendanceResponse::getFullName,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return rows;
    }

    private AttendanceResponse toResponse(Attendance record, Employee employee) {
        Integer worked = record.getWorkedMinutes();
        return AttendanceResponse.builder()
                .id(record.getId())
                .employeeUserId(record.getEmployeeUserId())
                .employeeCode(employee.getEmployeeCode())
                .fullName(employee.getFullName())
                .workDate(record.getWorkDate())
                .checkInAt(record.getCheckInAt())
                .checkOutAt(record.getCheckOutAt())
                .sessionStartedAt(record.getSessionStartedAt())
                .workedMinutes(worked)
                .status(record.getStatus())
                .lateByMinutes(record.getLateByMinutes())
                .fullDay(worked == null ? null : worked >= props.getFullDayMinHours() * 60)
                .source(record.getSource())
                .workMode(employee.getWorkMode())
                .timezone(record.getTimezone())
                .build();
    }
}
