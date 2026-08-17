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
import java.util.Set;
import java.util.UUID;

/**
 * Web Clock-In / Check-in: any employee working remotely can self-declare a check-in — no
 * manager approval needed, it upserts the day's {@link Attendance} row (source WEB_REMOTE)
 * immediately. {@code approve}/{@code reject} remain only to let a manager clear out any
 * pre-existing PENDING rows from before this changed; nothing new is ever submitted as
 * PENDING anymore, so those two paths are otherwise dead going forward. Check-out and cancel
 * (undo today's check-in before checking out) both need no approval either.
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
        LocalDateTime now = now(actor.getId());
        LocalDate today = shiftDayOf(now);

        if (webClockInRepository.existsByEmployeeUserIdAndWorkDateAndStatus(actor.getId(), today, "APPROVED")) {
            throw new IllegalArgumentException("You have already checked in today");
        }

        // No manager approval needed — self-approved the moment it's submitted.
        WebClockInRequest entity = WebClockInRequest.builder()
                .employeeUserId(actor.getId())
                .assignedApproverId(resolveAssignedApprover(actor.getId()))
                .workDate(today)
                .requestedCheckIn(now)
                .reason(req.getReason().trim())
                .status("APPROVED")
                .reviewedBy(actor.getId())
                .reviewedAt(now)
                .build();
        entity = webClockInRepository.save(entity);

        applyCheckInToAttendance(entity);

        auditService.log(actor.getId(), "WEB_CLOCK_IN_CHECKED_IN", entity.getId());
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
                .findFirstByEmployeeUserIdAndStatusAndCheckedOutAtIsNullOrderByWorkDateDesc(actor.getId(), "APPROVED")
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
     * Only reachable for a PENDING row that predates the no-approval-needed change — nothing
     * new is ever submitted as PENDING anymore, see {@link #submit}.
     */
    @Transactional
    public WebClockInResponse approve(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        WebClockInRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        applyCheckInToAttendance(req);

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
     * No approval needed — the employee closes out their own approved web clock-in day.
     * Mirrors AttendanceService.checkOut's derived-field logic for a single-session day.
     */
    @Transactional
    public WebClockInResponse checkOut(String actorEmail) {
        User actor = requireActor(actorEmail);

        // Looked up by "approved and not yet checked out", not by today's work_date — a web
        // clock-in approved before midnight (shift crosses into the next day) can still be
        // open under yesterday's work_date once the calendar date rolls over.
        WebClockInRequest req = webClockInRepository
                .findFirstByEmployeeUserIdAndStatusAndCheckedOutAtIsNullOrderByWorkDateDesc(actor.getId(), "APPROVED")
                .orElseThrow(() -> new IllegalArgumentException("No approved web clock-in found for today"));

        Attendance record = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(actor.getId(), req.getWorkDate())
                .orElseThrow(() -> new IllegalStateException("Attendance record missing for an approved web clock-in"));

        LocalDateTime now = now(actor.getId());

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
     * Upserts the day's Attendance row from a (now-always-approved) request's requestedCheckIn.
     * If a record already exists for the day, this is a same-day resume (e.g. the employee
     * already worked an office session earlier and checked out, then later needs to check in
     * again remotely) — mirrors AttendanceService.checkIn's resume path: the day's original
     * checkInAt, late status, and worked-minutes-so-far all stay put, only a new session opens
     * for checkOut to pick back up. Overwriting checkInAt here would silently erase whatever was
     * already recorded for the day.
     */
    private void applyCheckInToAttendance(WebClockInRequest req) {
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
     * Clock for a specific employee's own web clock-in actions — computed in THEIR configured
     * location's timezone, not the single global business zone. Mirrors
     * AttendanceService.now(Employee)/zoneIdFor. Falls back to the global zone when the
     * employee has no location assigned, or their location has no timezone configured.
     */
    private LocalDateTime now(UUID employeeUserId) {
        ZoneId zoneId = employeeRepository.findById(employeeUserId)
                .map(Employee::getLocation)
                .map(Location::getTimezone)
                .filter(tz -> tz != null && !tz.isBlank())
                .map(ZoneId::of)
                .orElseGet(() -> ZoneId.of(attendanceProps.getZone()));
        return LocalDateTime.now(zoneId);
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

    private void recomputeDerivedFields(Attendance record, UUID employeeUserId) {
        LocalTime deadline = resolveShiftStart(employeeUserId).plusMinutes(attendanceProps.getLateGraceMinutes());
        int lateByMinutes = record.getCheckInAt().toLocalTime().isAfter(deadline)
                ? (int) Duration.between(deadline, record.getCheckInAt().toLocalTime()).toMinutes()
                : 0;
        record.setLateByMinutes(lateByMinutes);
        record.setWorkedMinutes(null);
        record.setCheckOutAt(null);
        record.setStatus(lateByMinutes > 0 ? STATUS_LATE : STATUS_PRESENT);
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
