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
import com.nforce.onehr.repository.AttendancePunchRepository;
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
 * attendance effect is deliberately decoupled from the request's own approval lifecycle: the
 * FIRST request of a given employee's shift/workday starts {@code PENDING} and is routed to the
 * employee's manager (or HR/Super Admin) for a real approve/reject decision via
 * {@link #approve}/{@link #reject} — it does not self-approve. Once that first request for the
 * shift/workday has been APPROVED, every later Web Clock-In cycle within the SAME shift/workday
 * is auto-approved on submit (see {@code submit}'s own comment) — it neither re-asks HR nor
 * creates another pending request, since the shift has already had its one real review. Check-out
 * and cancel need no approval either, and neither is gated by review status at all (PENDING,
 * APPROVED, or REJECTED) — whether HR has looked at the request yet is independent of the
 * employee actually finishing (or undoing) their own real session.
 *
 * <p>Web Clock-In/Out is deliberately independent of normal Check-In/Check-Out: each is tracked
 * via its own open/closed state (this class's own WebClockInRequest.checkedOutAt vs.
 * AttendanceService's AttendancePunch.checkOutAt) and neither blocks on, or is blocked by, the
 * other — an employee can be normally checked in AND have an open Web Clock-In session at the
 * same time. Submitting or checking out a Web Clock-In session must never flip the normal
 * Check-In/Check-Out status (canCheckIn/canCheckOut) shown on the dashboard — see
 * AttendanceService.getToday, which derives that purely from AttendancePunch. The two sources'
 * worked time is still combined into one total without double-counting any overlap — see
 * AttendanceService.recomputeCombinedWorkedMinutes, called from both this class's checkOut and
 * AttendanceService's own.
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
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String SOURCE_WEB_REMOTE = "WEB_REMOTE";

    private final WebClockInRequestRepository webClockInRepository;
    private final AttendanceRepository attendanceRepository;
    private final AttendancePunchRepository attendancePunchRepository;
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
    // Only for recomputeCombinedWorkedMinutes — the single shared source of truth for merging
    // Check-In/Out + Web Clock-In/Out into one overlap-safe total. No other coupling: this class
    // never reads AttendanceService's own open/closed (canCheckIn/canCheckOut) state.
    private final AttendanceService attendanceService;

    @Transactional
    public WebClockInResponse submit(CreateWebClockInRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);

        // Only an already-open WEB session blocks a fresh Web Clock-In — deliberately independent
        // of whether a normal Check-In is currently open (see this class's own Javadoc). This is
        // NOT a once-per-day restriction — an employee may Web Clock-In and Web Clock-Out more
        // than once in the same day (see collectPunches), this only blocks trying to open a
        // *second, concurrent* Web session while one is already running. A Web session left open
        // past its own workday/grace window (a forgotten Web Clock-Out from days ago) is stale,
        // not really "still checked in" — auto-close it at its own natural shift end (mirrors
        // AttendanceService.checkIn's Missing-Check-Out staleness bypass, adapted here as an
        // auto-close since WebClockInRequest's own `status` field is a review status, not an
        // attendance-completion one) instead of letting it block Web Clock-In forever.
        Optional<WebClockInRequest> openWebSession = webClockInRepository.findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(actor.getId());
        if (openWebSession.isPresent()) {
            WebClockInRequest openReq = openWebSession.get();
            Attendance openReqAttendance = attendanceRepository
                    .findByEmployeeUserIdAndWorkDate(actor.getId(), openReq.getWorkDate()).orElse(null);
            LocalDateTime openNow = LocalDateTime.now(resolveZone(openReqAttendance, actor.getId(), req.getTimezone()));
            if (shiftDayOf(openNow).isAfter(openReq.getWorkDate())) {
                autoCloseStaleWebSession(openReq, openReqAttendance);
            } else {
                throw new IllegalArgumentException("You have already checked in today");
            }
        }

        // Resolved from the employee's own configured Location.timezone — mirrors
        // AttendanceService.checkIn's resolveZone (see its own doc comment: req.getTimezone(),
        // the browser-reported zone, is never consulted). Locked into the Attendance row this
        // creates/resumes (see applyCheckInToAttendance) for the rest of that session's lifetime.
        ZoneId zone = resolveZone(req.getTimezone(), actor.getId());
        LocalDateTime now = LocalDateTime.now(zone);
        LocalDate today = shiftDayOf(now);

        // Once THIS employee's FIRST Web Clock-In of the shift/workday has been reviewed and
        // APPROVED, every later Web Clock-In cycle the same shift/workday is auto-approved and
        // never re-notifies HR — only the very first request of a shift needs a real HR decision.
        // Keyed on employee + workDate (the shift's own resolved workday, same key
        // findOpenByEmployeeUserId/collectPunches already use for "same shift"), not on the
        // button click itself, so this survives any number of Web Clock-In/Out cycles within the
        // one shift/workday.
        boolean alreadyApprovedThisShift = webClockInRepository
                .findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(actor.getId(), today).stream()
                .anyMatch(r -> STATUS_APPROVED.equals(r.getStatus()));

        UUID approverId = resolveAssignedApprover(actor.getId());
        WebClockInRequest entity = WebClockInRequest.builder()
                .employeeUserId(actor.getId())
                .assignedApproverId(approverId)
                .workDate(today)
                .requestedCheckIn(now)
                .reason(req.getReason().trim())
                .status(alreadyApprovedThisShift ? STATUS_APPROVED : STATUS_PENDING)
                .build();
        if (alreadyApprovedThisShift) {
            entity.setReviewedAt(now);
            entity.setReviewComment("Auto-approved — a prior Web Clock-In this shift was already approved.");
        }
        entity = webClockInRepository.save(entity);

        // The attendance effect is immediate and real-time — the employee is checked in and
        // worked time starts accruing the moment they submit, regardless of how long HR takes to
        // review. See this class's own Javadoc for why these two things are deliberately
        // decoupled.
        applyCheckInToAttendance(entity, zone.getId());

        auditService.log(actor.getId(), "WEB_CLOCK_IN_CHECKED_IN", entity.getId());

        if (approverId != null && !alreadyApprovedThisShift) {
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

        // Only delete the day's shared Attendance row if THIS submission was the one that
        // created it fresh (source WEB_REMOTE, checkInAt matches this exact request) AND nothing
        // else has touched it since — no normal punches at all (open or closed), meaning nothing
        // else depends on it. Attendance/Web Clock-In are independent now (see this class's own
        // Javadoc), so a normal Check-In may have opened its own session on this same row after
        // this web submission; deleting the row out from under an unrelated open/closed normal
        // punch would corrupt that punch's own attendanceRecordId reference.
        attendanceRepository.findByEmployeeUserIdAndWorkDate(actor.getId(), req.getWorkDate())
                .filter(a -> SOURCE_WEB_REMOTE.equals(a.getSource())
                        && req.getRequestedCheckIn().equals(a.getCheckInAt())
                        && attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(a.getId()).isEmpty())
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
     * regardless of whether HR has reviewed it yet. Deliberately independent of the normal
     * Check-In/Check-Out session (see this class's own Javadoc): never reads or writes
     * Attendance.checkOutAt (that field belongs exclusively to the normal session now — see
     * AttendanceService.findOpenNormalAttendance) and never blocks on, or is blocked by, whatever
     * the normal side's own state happens to be.
     */
    @Transactional
    public WebClockInResponse checkOut(String actorEmail, String clientTimezone) {
        User actor = requireActor(actorEmail);

        // Looked up by "not yet checked out" regardless of review status, not by today's
        // work_date — a web clock-in from before midnight (shift crosses into the next day) can
        // still be open under yesterday's work_date once the calendar date rolls over. This is
        // this Web session's OWN open/closed signal — entirely independent of whether a normal
        // Check-In session happens to also be open right now.
        WebClockInRequest req = webClockInRepository
                .findFirstByEmployeeUserIdAndCheckedOutAtIsNullOrderByWorkDateDesc(actor.getId())
                .orElseThrow(() -> new IllegalArgumentException("No web clock-in found for today"));

        Attendance record = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(actor.getId(), req.getWorkDate())
                .orElseThrow(() -> new IllegalStateException("Attendance record missing for a web clock-in"));

        // The session's own zone, locked in at Web Clock-In (via the shared Attendance row's
        // timezone, set by whichever source first created it that day) — NOT this click's browser
        // zone, which may have drifted since (travel, DST). Mirrors AttendanceService.checkOut's
        // resolveZone.
        LocalDateTime now = LocalDateTime.now(resolveZone(record, actor.getId(), clientTimezone));

        // Past its own workday/grace window (shiftDayCutover, e.g. 7:00 AM the next calendar day)
        // — checked purely against THIS Web session's own workDate, deliberately not coupled to
        // the shared Attendance.status field (which is now reserved for the normal session's own
        // Missing-Check-Out flagging — see AttendanceService.flagMissingCheckoutIfStale): an
        // unrelated stale normal session sharing the same day must never block a legitimate,
        // timely Web Clock-Out, and vice versa.
        if (shiftDayOf(now).isAfter(req.getWorkDate())) {
            throw new IllegalArgumentException(
                    "This session is past its check-out window. Please submit a regularization request.");
        }

        // The shift's own natural end still bounds the WORKED-MINUTES figure — a forgotten
        // checkout left open for hours must not inflate into something like "27h 8m" for what's
        // supposed to be a single shift/day. But it must never be used as the recorded
        // checkedOutAt itself: the actual click time (`now`) is always what gets stored — shift
        // timing only feeds the capped aggregate below (recomputeCombinedWorkedMinutes's capAt),
        // never the timestamp. Mirrors AttendanceService.checkOut/closeSession.
        LocalDateTime cutoff = shiftEndCutoff(actor.getId(), req.getWorkDate());

        String before = auditSnapshot.toJson(Map.of("checkedOutAt", "null"));
        req.setCheckedOutAt(now);
        webClockInRepository.save(req);

        // Combined total across BOTH Check-In/Out and Web Clock-In/Out, overlap-safe — see
        // AttendanceService.recomputeCombinedWorkedMinutes. Normal and Web sessions are
        // independent and can genuinely overlap in real time, so this is a merge, not a running
        // "+= this session's minutes" (that would double-count any overlapping window).
        int workedMinutes = attendanceService.recomputeCombinedWorkedMinutes(actor.getId(), record.getId(), record.getWorkDate(), cutoff);
        record.setWorkedMinutes(workedMinutes);
        // Never touch record.checkOutAt here — see this method's own Javadoc. Status
        // (PRESENT/LATE/HALF_DAY) IS recomputed from the new combined total, since that
        // classification is about the day's overall worked-time sufficiency, not the normal
        // session's open/closed state — but a Missing-Check-Out flag already set by the normal
        // side's own staleness detection is left alone, not silently overwritten by this
        // unrelated Web checkout.
        if (!STATUS_MISSING_CHECKOUT.equals(record.getStatus())) {
            record.setStatus(workedMinutes < attendanceProps.getHalfDayMaxHours() * 60
                    ? STATUS_HALF_DAY
                    : (record.getLateByMinutes() > 0 ? STATUS_LATE : STATUS_PRESENT));
        }
        attendanceRepository.save(record);

        String after = auditSnapshot.toJson(Map.of("checkedOutAt", now.toString(), "workedMinutes", workedMinutes));
        auditService.log(actor.getId(), "WEB_CLOCK_OUT", req.getId(), before, after);
        return toResponse(req);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Ensures the day's Attendance row exists, so this session has somewhere to record its
     * eventual checkout/worked-minutes contribution. Deliberately does NOT touch checkInAt/
     * checkOutAt/sessionStartedAt on an already-existing row — those fields belong exclusively to
     * the NORMAL Check-In/Check-Out session now (see AttendanceService.findOpenNormalAttendance);
     * a Web Clock-In session is fully independent and tracks its own open/closed state entirely
     * on WebClockInRequest.requestedCheckIn/checkedOutAt, never by mutating the shared row. If no
     * row exists yet for the day, THIS is the day's first-ever punch (from either source) —
     * create it and compute lateness/status/penalty exactly as AttendanceService.checkIn would,
     * so a remote-only day is still evaluated for lateness like any other.
     */
    private void applyCheckInToAttendance(WebClockInRequest req, String resolvedZoneId) {
        boolean alreadyExists = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(req.getEmployeeUserId(), req.getWorkDate()).isPresent();
        if (alreadyExists) {
            return;
        }
        Attendance record = Attendance.builder()
                .employeeUserId(req.getEmployeeUserId())
                .workDate(req.getWorkDate())
                .checkInAt(req.getRequestedCheckIn())
                .sessionStartedAt(req.getRequestedCheckIn())
                .timezone(resolvedZoneId)
                .build();
        record.setSource(SOURCE_WEB_REMOTE);
        recomputeDerivedFields(record, req.getEmployeeUserId());
        Attendance saved = attendanceRepository.save(record);

        // Same penalty as AttendanceService.checkIn — a fresh late arrival costs a half-day every
        // 3rd time in the month, regardless of whether the check-in was in-office or remote.
        if (STATUS_LATE.equals(saved.getStatus())) {
            employeeRepository.findById(req.getEmployeeUserId())
                    .ifPresent(employee -> latePenaltyService.applyIfDue(employee, req.getWorkDate()));
        }
    }

    /**
     * A Web Clock-In session left open past its own workday/grace window (a forgotten Web
     * Clock-Out from days ago) — auto-closed at its own natural shift end (same cap a real Web
     * Clock-Out would apply, see shiftEndCutoff) rather than left open forever blocking a fresh
     * Web Clock-In. Recomputes the day's combined worked minutes afterward so the auto-close is
     * correctly reflected in the total.
     */
    private void autoCloseStaleWebSession(WebClockInRequest staleReq, Attendance staleReqAttendance) {
        LocalDateTime cutoff = shiftEndCutoff(staleReq.getEmployeeUserId(), staleReq.getWorkDate());
        staleReq.setCheckedOutAt(cutoff);
        webClockInRepository.save(staleReq);
        if (staleReqAttendance != null) {
            int workedMinutes = attendanceService.recomputeCombinedWorkedMinutes(
                    staleReq.getEmployeeUserId(), staleReqAttendance.getId(), staleReqAttendance.getWorkDate());
            staleReqAttendance.setWorkedMinutes(workedMinutes);
            attendanceRepository.save(staleReqAttendance);
        }
        auditService.log(staleReq.getEmployeeUserId(), "WEB_CLOCK_IN_AUTO_CLOSED_STALE", staleReq.getId());
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
     * Zone for a fresh Web Clock-In click: ALWAYS the employee's own configured
     * Location.timezone (falling back only to the global business zone if unconfigured).
     * {@code clientTimezone} (the browser-reported zone, still sent by the frontend on every Web
     * Clock action) is deliberately never consulted — per explicit requirement, the employee's
     * assigned Location timezone is the ONLY source of truth for their attendance clock, and Web
     * Clock must use the exact same source as normal Check-In/Check-Out, never a different one.
     * Mirrors AttendanceService.resolveZone(String, Employee).
     */
    private ZoneId resolveZone(String clientTimezone, UUID employeeUserId) {
        return zoneIdFor(employeeUserId);
    }

    /**
     * Zone for an EXISTING session — its own Attendance.timezone, locked in at Check-In/Web
     * Clock-In (itself already Location-derived — see the other resolveZone overload), governs
     * Check-Out/grace-window/worked-minutes math for as long as it's open. Falls back to the
     * employee's current Location.timezone only for a record predating this column;
     * clientTimezoneFallback is likewise never consulted, for the same reason as above. Mirrors
     * AttendanceService.resolveZone(Attendance, Employee, String).
     */
    private ZoneId resolveZone(Attendance record, UUID employeeUserId, String clientTimezoneFallback) {
        ZoneId stored = record != null ? parseZone(record.getTimezone()) : null;
        return stored != null ? stored : zoneIdFor(employeeUserId);
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
