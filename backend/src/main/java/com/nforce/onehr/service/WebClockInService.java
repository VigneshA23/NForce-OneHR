package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.CreateWebClockInRequest;
import com.nforce.onehr.dto.attendance.WebClockInResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.entity.WebClockInRequest;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Web Clock-In / Check-in: any employee working remotely can self-declare a check-in — it
 * upserts the day's {@link Attendance} row (source WEB_REMOTE) and starts counting worked time
 * immediately, the same instant it's submitted, with NO wait for HR review. That immediate
 * attendance effect is deliberately decoupled from the request's own approval lifecycle: every
 * new request starts {@code PENDING} and is routed to the employee's manager (or HR/Super Admin)
 * for a real approve/reject decision via {@link #approve}/{@link #reject} — it does not
 * self-approve. Check-out and cancel need no approval either, and neither is gated by review
 * status at all (PENDING, APPROVED, or REJECTED) — whether HR has looked at the request yet is
 * independent of the employee actually finishing (or undoing) their own real session.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebClockInService {

    private static final Set<String> APPROVER_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final String STATUS_PRESENT = "PRESENT";
    private static final String STATUS_LATE = "LATE";
    private static final String STATUS_HALF_DAY = "HALF_DAY";
    // Mirrors AttendanceService.STATUS_MISSING_CHECKOUT — see checkOut's own doc comment.
    private static final String STATUS_MISSING_CHECKOUT = "MISSING_CHECKOUT";
    private static final String STATUS_PENDING = "PENDING";
    private static final String SOURCE_WEB_REMOTE = "WEB_REMOTE";

    private final WebClockInRequestRepository webClockInRepository;
    private final AttendanceRepository attendanceRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final AttendanceProperties attendanceProps;
    // Shared with AttendanceService so the every-3rd-late-arrival penalty applies identically
    // regardless of check-in entry point — see LatePenaltyService.
    private final LatePenaltyService latePenaltyService;
    private final NotificationService notificationService;

    @Transactional
    public WebClockInResponse submit(CreateWebClockInRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);

        // An already-open session — from EITHER entry point, a regular Check-In or a still-open
        // earlier Web Clock-In — must block a fresh Web Clock-In exactly like AttendanceService
        // .checkIn blocks a fresh Check-In: only one session open at a time, however it started.
        // This is NOT a once-per-day restriction — an employee may Web Clock-In and Web Clock-Out
        // more than once in the same day (see collectPunches), this only blocks trying to open a
        // *second, concurrent* session while one is already running. A session left open past
        // its own workday/grace window (a forgotten checkout from days ago) is stale, not really
        // "still checked in" — flag it Missing Check-Out (mirrors AttendanceService.checkIn /
        // flagMissingCheckoutIfStale) instead of letting it block Web Clock-In forever.
        Optional<Attendance> openSession = attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(actor.getId());
        if (openSession.isPresent()) {
            LocalDateTime openNow = LocalDateTime.now(resolveZone(openSession.get(), actor.getId(), req.getTimezone()));
            if (!flagMissingCheckoutIfStale(openSession.get(), openNow)) {
                throw new IllegalArgumentException("You have already checked in today");
            }
        }

        // Resolved from the browser-reported zone (req.getTimezone(), e.g. from Intl
        // .DateTimeFormat().resolvedOptions().timeZone), falling back to the employee's
        // configured Location.timezone — mirrors AttendanceService.checkIn's resolveZone. Locked
        // into the Attendance row this creates/resumes (see applyCheckInToAttendance) for the
        // rest of that session's lifetime.
        ZoneId zone = resolveZone(req.getTimezone(), actor.getId());
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDate today = shiftDayOf(now);

        UUID approverId = resolveAssignedApprover(actor.getId());
        WebClockInRequest entity = WebClockInRequest.builder()
                .employeeUserId(actor.getId())
                .assignedApproverId(approverId)
                .workDate(today)
                .requestedCheckIn(now)
                .reason(req.getReason().trim())
                .status(STATUS_PENDING)
                .build();
        entity = webClockInRepository.save(entity);

        // The attendance effect is immediate and real-time — the employee is checked in and
        // worked time starts accruing the moment they submit, regardless of how long HR takes to
        // review. See this class's own Javadoc for why these two things are deliberately
        // decoupled.
        applyCheckInToAttendance(entity, zone.getId());

        auditService.log(actor.getId(), "WEB_CLOCK_IN_CHECKED_IN", entity.getId());

        if (approverId != null) {
            notificationService.send(approverId, "WEB_CLOCK_IN_SUBMITTED",
                    "Web Clock-In Request Submitted",
                    employeeName(actor.getId()) + " has submitted a web clock-in request for " + today + ".",
                    "/approvals?type=WEB_CLOCK_IN");
        }
        return toResponse(entity);
    }

    /**
     * Undoes today's still-open check-in (before check-out) — deletes both the request and the
     * Attendance row it created, as if the check-in never happened. Only ever touches a row this
     * same flow created (source WEB_REMOTE, not yet checked out); if the employee had already
     * checked out, there's nothing left to cancel (checkOut clears the lookup this relies on).
     */
    @Transactional
    public void cancel(String actorEmail) {
        User actor = requireActor(actorEmail);
        WebClockInRequest req = webClockInRepository
                .findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(actor.getId())
                .orElseThrow(() -> new IllegalArgumentException("No active check-in to cancel"));

        attendanceRepository.findByEmployeeUserIdAndWorkDate(actor.getId(), req.getWorkDate())
                .filter(a -> SOURCE_WEB_REMOTE.equals(a.getSource()) && a.getCheckOutAt() == null)
                .ifPresent(attendanceRepository::delete);

        auditService.log(actor.getId(), "WEB_CLOCK_IN_CANCELLED", req.getId());
        webClockInRepository.delete(req);
    }

    @Transactional(readOnly = true)
    public List<WebClockInResponse> listMine(String actorEmail) {
        User actor = requireActor(actorEmail);
        return webClockInRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId())
                .stream().map(this::toResponse).toList();
    }

    /** Manager sees only requests assigned to them; HR/Super Admin see all pending requests. */
    @Transactional(readOnly = true)
    public List<WebClockInResponse> listPendingForApprover(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<WebClockInRequest> pending = webClockInRepository.findByStatus("PENDING");

        if (hasOverrideRole(actor)) {
            return pending.stream().map(this::toResponse).toList();
        }
        return pending.stream()
                .filter(r -> actor.getId().equals(r.getAssignedApproverId()))
                .map(this::toResponse)
                .toList();
    }

    /**
     * HR/manager approval of a Web Clock-In request. The attendance effect (the Attendance row,
     * worked minutes, punch history) was ALREADY applied the moment the employee submitted — see
     * {@link #submit}'s own doc comment — so this only ever updates the request's own review
     * status; it must NOT re-touch the Attendance row. Re-applying it here would silently reopen
     * a session the employee may have already checked out of (or resumed since), reintroducing
     * exactly the double-counting bug fixed in checkOut's own "already closed elsewhere" guard.
     */
    @Transactional
    public WebClockInResponse approve(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        WebClockInRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        String before = auditSnapshot.toJson(Map.of("status", "PENDING"));
        req.setStatus("APPROVED");
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        webClockInRepository.save(req);

        String after = auditSnapshot.toJson(Map.of("status", "APPROVED", "reviewComment", comment != null ? comment : ""));
        auditService.log(actor.getId(), "WEB_CLOCK_IN_APPROVED", req.getEmployeeUserId(), before, after);

        notificationService.send(req.getEmployeeUserId(), "WEB_CLOCK_IN_APPROVED",
                "Web Clock-In Approved",
                "Your web clock-in for " + req.getWorkDate() + " has been approved by " + employeeName(actor.getId()) + ".",
                "/requests?type=WEB_CLOCK_IN");
        return toResponse(req);
    }

    @Transactional
    public WebClockInResponse reject(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        WebClockInRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        String before = auditSnapshot.toJson(Map.of("status", "PENDING"));
        req.setStatus("REJECTED");
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        webClockInRepository.save(req);

        String after = auditSnapshot.toJson(Map.of("status", "REJECTED", "reviewComment", comment != null ? comment : ""));
        auditService.log(actor.getId(), "WEB_CLOCK_IN_REJECTED", req.getEmployeeUserId(), before, after);

        notificationService.send(req.getEmployeeUserId(), "WEB_CLOCK_IN_REJECTED",
                "Web Clock-In Rejected",
                "Your web clock-in for " + req.getWorkDate() + " has been rejected by " + employeeName(actor.getId())
                        + (comment != null && !comment.isBlank() ? ". Reason: " + comment.trim() : "."),
                "/requests?type=WEB_CLOCK_IN");
        return toResponse(req);
    }

    /**
     * No approval needed to check out — the employee closes out their own web clock-in day
     * regardless of whether HR has reviewed it yet. Mirrors AttendanceService.checkOut's
     * derived-field logic for a single-session day.
     */
    @Transactional
    public WebClockInResponse checkOut(String actorEmail, String clientTimezone) {
        User actor = requireActor(actorEmail);

        // Looked up by "not yet checked out" regardless of review status, not by today's
        // work_date — a web clock-in from before midnight (shift crosses into the next day) can
        // still be open under yesterday's work_date once the calendar date rolls over.
        WebClockInRequest req = webClockInRepository
                .findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(actor.getId())
                .orElseThrow(() -> new IllegalArgumentException("No web clock-in found for today"));

        Attendance record = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(actor.getId(), req.getWorkDate())
                .orElseThrow(() -> new IllegalStateException("Attendance record missing for an approved web clock-in"));

        // The underlying session can also be closed through the OTHER entry point — a regular
        // Check-Out — while this request is still marked open, since both share the same
        // Attendance row. If that already happened, the record's own checkOutAt is the source
        // of truth: sync this request to it and stop, rather than recomputing a second,
        // overlapping session on top of one already closed and counted — that would both
        // double-count worked minutes and push checkOutAt later than what actually happened.
        if (record.getCheckOutAt() != null) {
            req.setCheckedOutAt(record.getCheckOutAt());
            webClockInRepository.save(req);
            return toResponse(req);
        }

        // The session's own zone, locked in at Web Clock-In — NOT this click's browser zone,
        // which may have drifted since (travel, DST) — governs its Check-Out, so worked-minutes
        // math and the grace-window check below stay on one consistent clock for the whole
        // session. clientTimezone only matters as a fallback for a record from before this
        // column existed. Mirrors AttendanceService.checkOut's resolveZone.
        LocalDateTime now = LocalDateTime.now(resolveZone(record, actor.getId(), clientTimezone));

        // Past its own workday/grace window (shiftDayCutover, e.g. 7:00 AM the next calendar
        // day) — mirrors AttendanceService.checkOut: there is no legitimate "now" to check out
        // with this long after the fact, so this is flagged Missing Check-Out (no fabricated
        // checkOutAt/workedMinutes) rather than silently accepted as a real, very-late checkout.
        // Corrected via the existing Regularization flow, same as any other attendance
        // correction.
        if (!STATUS_MISSING_CHECKOUT.equals(record.getStatus()) && shiftDayOf(now).isAfter(req.getWorkDate())) {
            String beforeMissing = auditSnapshot.toJson(Map.of("status", record.getStatus()));
            record.setStatus(STATUS_MISSING_CHECKOUT);
            attendanceRepository.save(record);
            String afterMissing = auditSnapshot.toJson(Map.of("status", STATUS_MISSING_CHECKOUT));
            auditService.log(actor.getId(), "ATTENDANCE_MISSING_CHECKOUT", record.getId(), beforeMissing, afterMissing);
        }
        if (STATUS_MISSING_CHECKOUT.equals(record.getStatus())) {
            throw new IllegalArgumentException(
                    "This session is past its check-out window and has been marked as a missing check-out. Please submit a regularization request.");
        }

        // A forgotten checkout can leave a session open for several hours before the employee
        // actually clicks Check-out (e.g. checking out at 3 AM for a shift that ended at
        // 12:30 AM); count worked time only up to this shift's own natural end (not the late
        // click's real clock time), so it can never inflate into something like "27h 8m" for what
        // is supposed to be a single shift/day — mirrors AttendanceService.checkOut's cap. Only
        // reachable here at all within the grace window above — a click that arrives after the
        // grace window is now rejected outright.
        LocalDateTime cutoff = shiftEndCutoff(actor.getId(), req.getWorkDate());
        LocalDateTime effectiveCheckOut = now.isAfter(cutoff) ? cutoff : now;

        String before = auditSnapshot.toJson(Map.of("checkedOutAt", "null"));
        req.setCheckedOutAt(effectiveCheckOut);
        webClockInRepository.save(req);

        // Sessions accumulate exactly like AttendanceService.checkOut: only this session's
        // minutes are added to whatever was already worked earlier today (e.g. an office
        // session before this remote one), so switching to Web Check-in mid-day doesn't lose
        // or double count time already logged.
        LocalDateTime sessionStart = record.getSessionStartedAt() != null
                ? record.getSessionStartedAt() : record.getCheckInAt();
        long sessionSeconds = Math.max(0, Duration.between(sessionStart, effectiveCheckOut).getSeconds());
        int sessionMinutes = (int) Math.round(sessionSeconds / 60.0);
        int workedMinutes = (record.getWorkedMinutes() != null ? record.getWorkedMinutes() : 0) + sessionMinutes;
        record.setCheckOutAt(effectiveCheckOut);
        record.setWorkedMinutes(workedMinutes);
        record.setStatus(workedMinutes < attendanceProps.getHalfDayMaxHours() * 60
                ? STATUS_HALF_DAY
                : (record.getLateByMinutes() > 0 ? STATUS_LATE : STATUS_PRESENT));
        attendanceRepository.save(record);

        String after = auditSnapshot.toJson(Map.of("checkedOutAt", effectiveCheckOut.toString(), "workedMinutes", workedMinutes));
        auditService.log(actor.getId(), "WEB_CLOCK_OUT", req.getId(), before, after);
        return toResponse(req);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Mirrors AttendanceService.flagMissingCheckoutIfStale — a session left open past its own
     * workday/grace window (shiftDayCutover, e.g. 7:00 AM the next calendar day) must not go on
     * blocking a fresh Web Clock-In ({@link #submit}) forever. Flags it Missing Check-Out right
     * then — deliberately WITHOUT fabricating a checkOutAt or computing workedMinutes — and
     * returns whether the record is now, or already was, flagged, so the caller can fall through
     * to treating this employee as having no open session.
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
     * Upserts the day's Attendance row from a (now-always-approved) request's requestedCheckIn.
     * If a record already exists for the day, this is a same-day resume (e.g. the employee
     * already worked an office session earlier and checked out, then later needs to check in
     * again remotely) — mirrors AttendanceService.checkIn's resume path: the day's original
     * checkInAt, late status, and worked-minutes-so-far all stay put, only a new session opens
     * for checkOut to pick back up. Overwriting checkInAt here would silently erase whatever was
     * already recorded for the day. {@code resolvedZoneId} is stored only on a fresh row — a
     * resume reuses whatever zone the day's original check-in already locked in, exactly like
     * AttendanceService.checkIn's resume path.
     */
    private void applyCheckInToAttendance(WebClockInRequest req, String resolvedZoneId) {
        Attendance record = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(req.getEmployeeUserId(), req.getWorkDate())
                .orElse(null);
        boolean isFreshCheckIn = record == null;
        if (isFreshCheckIn) {
            record = Attendance.builder()
                    .employeeUserId(req.getEmployeeUserId())
                    .workDate(req.getWorkDate())
                    .checkInAt(req.getRequestedCheckIn())
                    .sessionStartedAt(req.getRequestedCheckIn())
                    .timezone(resolvedZoneId)
                    .build();
            record.setSource(SOURCE_WEB_REMOTE);
            recomputeDerivedFields(record, req.getEmployeeUserId());
        } else {
            record.setSource(SOURCE_WEB_REMOTE);
            record.setSessionStartedAt(req.getRequestedCheckIn());
            record.setCheckOutAt(null);
        }
        Attendance saved = attendanceRepository.save(record);

        // Same penalty as AttendanceService.checkIn — a fresh late arrival costs a half-day
        // every 3rd time in the month, regardless of whether the check-in was in-office or
        // remote. Never fires on a same-day resume (isFreshCheckIn false): lateness is a
        // once-per-day fact tied to the day's first check-in.
        if (isFreshCheckIn && STATUS_LATE.equals(saved.getStatus())) {
            employeeRepository.findById(req.getEmployeeUserId())
                    .ifPresent(employee -> latePenaltyService.applyIfDue(employee, req.getWorkDate()));
        }
    }

    /**
     * Natural end of the shift covering workDate, crossing into the next calendar day when the
     * configured end time is earlier than the start (e.g. 3:30 PM - 12:30 AM) — see
     * AttendanceService.shiftEndCutoff.
     */
    private LocalDateTime shiftEndCutoff(UUID employeeUserId, LocalDate workDate) {
        LocalTime shiftStart = resolveShiftStart(employeeUserId);
        LocalTime shiftEnd = employeeRepository.findById(employeeUserId)
                .map(Employee::getShift)
                .map(Shift::getEndTime)
                .orElse(null);
        if (shiftEnd == null) {
            return LocalDateTime.of(workDate, shiftStart).plusHours(24);
        }
        LocalDate endDate = !shiftEnd.isAfter(shiftStart) ? workDate.plusDays(1) : workDate;
        return LocalDateTime.of(endDate, shiftEnd);
    }

    /**
     * The employee's configured Location.timezone, falling back to the global business zone —
     * mirrors AttendanceService.zoneIdFor. Used only when there's no browser-reported (or
     * session-locked) zone to prefer — see resolveZone.
     */
    private ZoneId zoneIdFor(UUID employeeUserId) {
        return employeeRepository.findById(employeeUserId)
                .map(Employee::getLocation)
                .map(Location::getTimezone)
                .filter(tz -> tz != null && !tz.isBlank())
                .map(ZoneId::of)
                .orElseGet(() -> ZoneId.of(attendanceProps.getZone()));
    }

    /**
     * Parses an IANA zone id (e.g. from the browser's {@code Intl.DateTimeFormat()
     * .resolvedOptions().timeZone}), or null if it's missing/blank/not a real zone — callers
     * fall back to {@link #zoneIdFor} rather than fail the request over a malformed value.
     * Mirrors AttendanceService.parseZone.
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
     * Zone for a fresh Web Clock-In click: the browser-reported zone if present and valid, else
     * the employee's configured Location.timezone (then the global business zone). Mirrors
     * AttendanceService.resolveZone(String, Employee).
     */
    private ZoneId resolveZone(String clientTimezone, UUID employeeUserId) {
        ZoneId fromClient = parseZone(clientTimezone);
        return fromClient != null ? fromClient : zoneIdFor(employeeUserId);
    }

    /**
     * Zone for an EXISTING session — its own Attendance.timezone, locked in at Check-In/Web
     * Clock-In, governs Check-Out/grace-window/worked-minutes math for as long as it's open, so
     * a browser reporting a different zone later (travel, DST) can't shift that session's
     * shift-day or inflate/shrink its worked hours. clientTimezoneFallback (and then
     * Location.timezone) only apply for a record from before this column existed. Mirrors
     * AttendanceService.resolveZone(Attendance, Employee, String).
     */
    private ZoneId resolveZone(Attendance record, UUID employeeUserId, String clientTimezoneFallback) {
        ZoneId stored = parseZone(record.getTimezone());
        return stored != null ? stored : resolveZone(clientTimezoneFallback, employeeUserId);
    }

    /**
     * The shift-day (work_date) a given instant belongs to — mirrors
     * AttendanceService.shiftDayOf. The shift runs 3:30 PM - 12:30 AM, crossing midnight;
     * anything from midnight up to shiftDayCutover (7:00 AM by default) still belongs to the
     * previous calendar date's shift-day.
     */
    private LocalDate shiftDayOf(LocalDateTime dateTime) {
        return dateTime.toLocalTime().isBefore(attendanceProps.getShiftDayCutover())
                ? dateTime.toLocalDate().minusDays(1)
                : dateTime.toLocalDate();
    }

    private UUID resolveAssignedApprover(UUID employeeId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .orElse(null);
    }

    /** The employee's actually-assigned Shift start (ONEHR-108) if present, else the global fallback. */
    private LocalTime resolveShiftStart(UUID employeeUserId) {
        return employeeRepository.findById(employeeUserId)
                .map(Employee::getShift)
                .map(Shift::getStartTime)
                .orElse(attendanceProps.getShiftStart());
    }

    /**
     * Mirrors AttendanceService.checkIn's identical two-independent-things split (see its own
     * doc comment): {@code isLate}/status is grace-aware (deadline = shiftStart + grace), while
     * {@code lateByMinutes} is the raw, no-forgiveness minutes past shiftStart itself shown to
     * the employee — these must NOT collapse into the same reference point, or a check-in one
     * second past the deadline would under-report how late it actually was by the whole grace
     * window. Both are compared as full date-aware instants (shiftStart anchored to the record's
     * own workDate — the already-resolved shift-day), NOT bare LocalTime-of-day: a pure LocalTime
     * comparison silently breaks the moment a check-in crosses midnight relative to an overnight
     * shift (e.g. a 20:30-05:30 shift's 1:11 AM check-in is genuinely hours late, but 01:11 as a
     * bare LocalTime reads as "before" 20:30).
     */
    private void recomputeDerivedFields(Attendance record, UUID employeeUserId) {
        LocalDateTime shiftStartAt = LocalDateTime.of(record.getWorkDate(), resolveShiftStart(employeeUserId));
        LocalDateTime deadlineAt = shiftStartAt.plusMinutes(attendanceProps.getLateGraceMinutes());
        LocalDateTime checkInAt = record.getCheckInAt();
        boolean isLate = checkInAt.isAfter(deadlineAt);
        int lateByMinutes = checkInAt.isAfter(shiftStartAt)
                ? (int) Math.ceil(Duration.between(shiftStartAt, checkInAt).getSeconds() / 60.0)
                : 0;
        record.setLateByMinutes(lateByMinutes);
        record.setWorkedMinutes(null);
        record.setCheckOutAt(null);
        // Status/penalty-relevant lateness is grace-aware (isLate) — matches AttendanceService
        // .checkIn: a check-in that's late by less than the grace window must not count as an
        // official LATE arrival just because lateByMinutes (its own no-forgiveness display
        // value) is nonzero.
        record.setStatus(isLate ? STATUS_LATE : STATUS_PRESENT);
    }

    private WebClockInRequest requirePending(UUID requestId) {
        WebClockInRequest req = webClockInRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new IllegalArgumentException("Only pending requests can be reviewed");
        }
        return req;
    }

    private void assertCanReview(WebClockInRequest req, User actor) {
        if (hasOverrideRole(actor)) return;
        if (actor.getId().equals(req.getAssignedApproverId())) return;

        boolean isManager = actor.getRoles().stream().anyMatch(r -> r.getCode().equals("MANAGER"));
        if (isManager && isCurrentManagerOf(actor.getId(), req.getEmployeeUserId())) return;

        throw new AccessDeniedException("You are not authorized to review this request");
    }

    private boolean hasOverrideRole(User actor) {
        return actor.getRoles().stream().anyMatch(r -> APPROVER_OVERRIDE_ROLES.contains(r.getCode()));
    }

    private boolean isCurrentManagerOf(UUID managerCandidateId, UUID employeeUserId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeUserId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .map(managerCandidateId::equals)
                .orElse(false);
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private String employeeName(UUID userId) {
        return employeeRepository.findById(userId).map(Employee::getFullName).orElse("Unknown");
    }

    private WebClockInResponse toResponse(WebClockInRequest req) {
        Employee employee = employeeRepository.findById(req.getEmployeeUserId()).orElse(null);
        String employeeName = employee != null ? employee.getFullName() : "Unknown";
        String departmentName = employee != null && employee.getDepartment() != null
                ? employee.getDepartment().getName() : null;
        String employeeEmail = userRepository.findById(req.getEmployeeUserId())
                .map(User::getEmail).orElse("");
        String reviewerName = req.getReviewedBy() == null ? null
                : employeeRepository.findById(req.getReviewedBy()).map(Employee::getFullName).orElse(null);
        String assignedApproverName = req.getAssignedApproverId() == null ? null
                : employeeRepository.findById(req.getAssignedApproverId()).map(Employee::getFullName).orElse(null);

        return WebClockInResponse.builder()
                .id(req.getId())
                .employeeUserId(req.getEmployeeUserId())
                .employeeName(employeeName)
                .employeeEmail(employeeEmail)
                .departmentName(departmentName)
                .workDate(req.getWorkDate())
                .requestedCheckIn(req.getRequestedCheckIn())
                .reason(req.getReason())
                .status(req.getStatus())
                .assignedApproverId(req.getAssignedApproverId())
                .assignedApproverName(assignedApproverName)
                .checkedOutAt(req.getCheckedOutAt())
                .reviewedByName(reviewerName)
                .reviewedAt(req.getReviewedAt())
                .reviewComment(req.getReviewComment())
                .createdAt(req.getCreatedAt())
                .build();
    }
}
