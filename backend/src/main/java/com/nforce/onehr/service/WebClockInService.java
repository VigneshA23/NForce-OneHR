package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.CreateWebClockInRequest;
import com.nforce.onehr.dto.attendance.WebClockInResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.Role;
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
 * Web Clock-In: any employee working remotely can self-declare a check-in with a reason,
 * routed via EmployeeManagerHistory to their current manager (or HR/Super Admin) for
 * approval — same shape as {@link RegularizationService}. Approval upserts the day's
 * {@link Attendance} row (source WEB_REMOTE); check-out afterward needs no approval.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebClockInService {

    private static final Set<String> APPROVER_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final String STATUS_PRESENT = "PRESENT";
    private static final String STATUS_LATE = "LATE";
    private static final String STATUS_HALF_DAY = "HALF_DAY";
    private static final String SOURCE_WEB_REMOTE = "WEB_REMOTE";

    private final WebClockInRequestRepository webClockInRepository;
    private final AttendanceRepository attendanceRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final AttendanceProperties attendanceProps;

    @Transactional
    public WebClockInResponse submit(CreateWebClockInRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LocalDateTime now = now();
        LocalDate today = now.toLocalDate();

        if (webClockInRepository.existsByEmployeeUserIdAndWorkDateAndStatus(actor.getId(), today, "PENDING")) {
            throw new IllegalArgumentException("A pending web clock-in request already exists for today");
        }
        if (webClockInRepository.existsByEmployeeUserIdAndWorkDateAndStatus(actor.getId(), today, "APPROVED")) {
            throw new IllegalArgumentException("You have already clocked in today");
        }

        WebClockInRequest entity = WebClockInRequest.builder()
                .employeeUserId(actor.getId())
                .assignedApproverId(resolveAssignedApprover(actor.getId()))
                .workDate(today)
                .requestedCheckIn(now)
                .reason(req.getReason().trim())
                .status("PENDING")
                .build();
        entity = webClockInRepository.save(entity);

        auditService.log(actor.getId(), "WEB_CLOCK_IN_REQUESTED", entity.getId());
        return toResponse(entity);
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

    @Transactional
    public WebClockInResponse approve(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        WebClockInRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        Attendance record = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(req.getEmployeeUserId(), req.getWorkDate())
                .orElse(null);
        if (record == null) {
            record = Attendance.builder()
                    .employeeUserId(req.getEmployeeUserId())
                    .workDate(req.getWorkDate())
                    .checkInAt(req.getRequestedCheckIn())
                    .build();
        } else {
            record.setCheckInAt(req.getRequestedCheckIn());
        }
        record.setSource(SOURCE_WEB_REMOTE);
        recomputeDerivedFields(record);
        attendanceRepository.save(record);

        String before = auditSnapshot.toJson(Map.of("status", "PENDING"));
        req.setStatus("APPROVED");
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        webClockInRepository.save(req);

        String after = auditSnapshot.toJson(Map.of("status", "APPROVED", "reviewComment", comment != null ? comment : ""));
        auditService.log(actor.getId(), "WEB_CLOCK_IN_APPROVED", req.getEmployeeUserId(), before, after);
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
        return toResponse(req);
    }

    /**
     * No approval needed — the employee closes out their own approved web clock-in day.
     * Mirrors AttendanceService.checkOut's derived-field logic for a single-session day.
     */
    @Transactional
    public WebClockInResponse checkOut(String actorEmail) {
        User actor = requireActor(actorEmail);
        LocalDate today = now().toLocalDate();

        WebClockInRequest req = webClockInRepository
                .findByEmployeeUserIdAndWorkDateAndStatus(actor.getId(), today, "APPROVED")
                .orElseThrow(() -> new IllegalArgumentException("No approved web clock-in found for today"));
        if (req.getCheckedOutAt() != null) {
            throw new IllegalArgumentException("You have already clocked out today");
        }

        Attendance record = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(actor.getId(), today)
                .orElseThrow(() -> new IllegalStateException("Attendance record missing for an approved web clock-in"));

        LocalDateTime now = now();
        String before = auditSnapshot.toJson(Map.of("checkedOutAt", "null"));
        req.setCheckedOutAt(now);
        webClockInRepository.save(req);

        record.setCheckOutAt(now);
        int workedMinutes = (int) Duration.between(record.getCheckInAt(), now).toMinutes();
        record.setWorkedMinutes(workedMinutes);
        record.setStatus(workedMinutes < attendanceProps.getHalfDayMaxHours() * 60
                ? STATUS_HALF_DAY
                : (record.getLateByMinutes() > 0 ? STATUS_LATE : STATUS_PRESENT));
        attendanceRepository.save(record);

        String after = auditSnapshot.toJson(Map.of("checkedOutAt", now.toString(), "workedMinutes", workedMinutes));
        auditService.log(actor.getId(), "WEB_CLOCK_OUT", req.getId(), before, after);
        return toResponse(req);
    }

    // ---------------------------------------------------------------- internals

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of(attendanceProps.getZone()));
    }

    private UUID resolveAssignedApprover(UUID employeeId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .orElse(null);
    }

    private void recomputeDerivedFields(Attendance record) {
        LocalTime deadline = attendanceProps.getShiftStart().plusMinutes(attendanceProps.getLateGraceMinutes());
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
