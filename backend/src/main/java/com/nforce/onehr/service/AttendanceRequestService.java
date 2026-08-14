package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.CreateAttendanceRequest;
import com.nforce.onehr.entity.AttendanceRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRequestRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Work From Home / Partial Day: a forward-looking self-declaration of work mode for a given
 * date — NOT a punch correction (contrast RegularizationService) and NOT an actual check-in
 * (contrast WebClockInService). Approval here only records a decision; it never writes to
 * attendance_records, since there's no shift-end (ONEHR-108) to reconcile worked hours against.
 *
 * Approver resolution intentionally reuses RegularizationService's two-argument, "assign to a
 * chosen approver else fall back to current manager" pattern (see resolveAssignedApprover)
 * rather than WebClockInService's simpler current-manager-only version — this is the explicit
 * product requirement to reuse the regularization approver-assignment pattern for this type.
 */
@Service
@RequiredArgsConstructor
public class AttendanceRequestService {

    private static final Set<String> APPROVER_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final Set<String> ELIGIBLE_APPROVER_ROLES = Set.of("MANAGER", "HR_ADMIN");
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String TYPE_WFH = "WFH";
    private static final String TYPE_PARTIAL_DAY = "PARTIAL_DAY";
    private static final Set<String> PARTIAL_DAY_MODES = Set.of("LATE_ARRIVE", "INTERVENING_TIMEOFF", "LEAVING_EARLY");

    // Partial Day's advisory allowance per calendar month, spendable on any day(s) within that
    // month. Not enforced as a hard cap in submit() — see resolvePartialDayHours — the employee
    // sees usage-vs-allowance via getPartialDayBalance, and the frontend asks them to confirm
    // before submitting past it; the assigned approver makes the actual call.
    private static final BigDecimal PARTIAL_DAY_MONTHLY_LIMIT_HOURS = new BigDecimal("2");

    private final AttendanceRequestRepository requestRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public AttendanceRequestResponse submit(CreateAttendanceRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        String type = normalizeType(req.getRequestType());
        BigDecimal partialDayHours = resolvePartialDayHours(type, req.getPartialDayHours());
        String partialDayMode = resolvePartialDayMode(type, req.getPartialDayMode());
        UUID notifyUserId = resolveNotifyUser(req.getNotifyUserId());

        AttendanceRequest entity = AttendanceRequest.builder()
                .employeeUserId(actor.getId())
                .assignedApproverId(resolveAssignedApprover(actor.getId(), req.getManagerUserId()))
                .requestType(type)
                .requestDate(req.getRequestDate())
                .partialDayHours(partialDayHours)
                .partialDayMode(partialDayMode)
                .notifyUserId(notifyUserId)
                .reason(req.getReason().trim())
                .status(STATUS_PENDING)
                .build();
        entity = requestRepository.save(entity);

        auditService.log(actor.getId(), "ATTENDANCE_REQUEST_SUBMITTED", entity.getId());
        if (notifyUserId != null) {
            Employee requester = employeeRepository.findById(actor.getId()).orElse(null);
            String requesterName = requester != null ? requester.getFullName() : "A colleague";
            notificationService.send(notifyUserId, "ATTENDANCE",
                    "Attendance request submitted",
                    requesterName + " submitted a " + (TYPE_WFH.equals(type) ? "Work From Home" : "Partial Day")
                            + " request for " + req.getRequestDate() + " and wanted you to know.",
                    "/attendance");
        }
        return toResponse(entity);
    }

    private String normalizeType(String requestType) {
        String type = requestType == null ? "" : requestType.trim().toUpperCase();
        if (!TYPE_WFH.equals(type) && !TYPE_PARTIAL_DAY.equals(type)) {
            throw new IllegalArgumentException("requestType must be WFH or PARTIAL_DAY");
        }
        return type;
    }

    /** LATE_ARRIVE | INTERVENING_TIMEOFF | LEAVING_EARLY — required for PARTIAL_DAY, ignored for WFH. */
    private String resolvePartialDayMode(String type, String partialDayMode) {
        if (TYPE_WFH.equals(type)) {
            return null;
        }
        String mode = partialDayMode == null ? "" : partialDayMode.trim().toUpperCase();
        if (!PARTIAL_DAY_MODES.contains(mode)) {
            throw new IllegalArgumentException("partialDayMode must be one of " + PARTIAL_DAY_MODES);
        }
        return mode;
    }

    private UUID resolveNotifyUser(UUID notifyUserId) {
        if (notifyUserId == null) return null;
        if (!userRepository.existsById(notifyUserId)) {
            throw new IllegalArgumentException("Selected employee to notify was not found");
        }
        return notifyUserId;
    }

    /** For the "View Available Balance" line: hours already committed this month vs. the cap. */
    @Transactional(readOnly = true)
    public PartialDayBalance getPartialDayBalance(String actorEmail, LocalDate forDate) {
        User actor = requireActor(actorEmail);
        BigDecimal used = partialDayHoursUsedInMonth(actor.getId(), forDate);
        return new PartialDayBalance(used, PARTIAL_DAY_MONTHLY_LIMIT_HOURS, PARTIAL_DAY_MONTHLY_LIMIT_HOURS.subtract(used).max(BigDecimal.ZERO));
    }

    public record PartialDayBalance(BigDecimal usedHours, BigDecimal limitHours, BigDecimal remainingHours) {}

    /**
     * The monthly cap (PARTIAL_DAY_MONTHLY_LIMIT_HOURS) is advisory, not enforced here — the
     * employee sees it via getPartialDayBalance and the frontend's "View Available Balance" /
     * over-balance confirmation, but submitting past it is still allowed (it just stays PENDING
     * for the assigned approver to judge, same as any other request). Only a structurally invalid
     * amount (zero or negative) is rejected.
     */
    private BigDecimal resolvePartialDayHours(String type, BigDecimal partialDayHours) {
        if (TYPE_WFH.equals(type)) {
            return null;
        }
        if (partialDayHours == null || partialDayHours.signum() <= 0) {
            throw new IllegalArgumentException("Partial day hours must be greater than zero");
        }
        return partialDayHours;
    }

    /**
     * Sum of partialDayHours across every non-rejected Partial Day request the employee has for
     * the calendar month requestDate falls in — PENDING requests count too (not just APPROVED),
     * since otherwise several simultaneously-pending requests could individually pass this check
     * and later all be approved past the monthly cap.
     */
    private BigDecimal partialDayHoursUsedInMonth(UUID employeeUserId, LocalDate requestDate) {
        LocalDate monthStart = requestDate.withDayOfMonth(1);
        LocalDate monthEnd = requestDate.withDayOfMonth(requestDate.lengthOfMonth());
        return requestRepository
                .findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(employeeUserId, TYPE_PARTIAL_DAY, monthStart, monthEnd)
                .stream()
                .filter(r -> !STATUS_REJECTED.equals(r.getStatus()))
                .map(AttendanceRequest::getPartialDayHours)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public List<AttendanceRequestResponse> listMine(String actorEmail) {
        User actor = requireActor(actorEmail);
        return requestRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId())
                .stream().map(this::toResponse).toList();
    }

    /** Manager sees only requests assigned to them; HR/Super Admin see all pending requests. */
    @Transactional(readOnly = true)
    public List<AttendanceRequestResponse> listPendingForApprover(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<AttendanceRequest> pending = requestRepository.findByStatus(STATUS_PENDING);

        if (hasOverrideRole(actor)) {
            return pending.stream().map(this::toResponse).toList();
        }
        return pending.stream()
                .filter(r -> actor.getId().equals(r.getAssignedApproverId()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AttendanceRequestResponse approve(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        AttendanceRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        req.setStatus(STATUS_APPROVED);
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        requestRepository.save(req);

        auditService.log(actor.getId(), "ATTENDANCE_REQUEST_APPROVED", req.getEmployeeUserId());
        return toResponse(req);
    }

    @Transactional
    public AttendanceRequestResponse reject(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        AttendanceRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        req.setStatus(STATUS_REJECTED);
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        requestRepository.save(req);

        auditService.log(actor.getId(), "ATTENDANCE_REQUEST_REJECTED", req.getEmployeeUserId());
        return toResponse(req);
    }

    // ---------------------------------------------------------------- internals

    /** Selected approver (validated as an eligible MANAGER/HR_ADMIN) else the employee's current manager. */
    private UUID resolveAssignedApprover(UUID employeeId, UUID managerUserId) {
        if (managerUserId != null) {
            User candidate = userRepository.findById(managerUserId)
                    .orElseThrow(() -> new IllegalArgumentException("Selected manager not found"));
            boolean eligible = candidate.getRoles().stream()
                    .anyMatch(r -> ELIGIBLE_APPROVER_ROLES.contains(r.getCode()));
            if (!eligible) {
                throw new IllegalArgumentException("Selected user is not an eligible approver");
            }
            return candidate.getId();
        }
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .orElse(null);
    }

    private AttendanceRequest requirePending(UUID requestId) {
        AttendanceRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (!STATUS_PENDING.equals(req.getStatus())) {
            throw new IllegalArgumentException("Only pending requests can be reviewed");
        }
        return req;
    }

    private void assertCanReview(AttendanceRequest req, User actor) {
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

    private AttendanceRequestResponse toResponse(AttendanceRequest req) {
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
        String notifyUserName = req.getNotifyUserId() == null ? null
                : employeeRepository.findById(req.getNotifyUserId()).map(Employee::getFullName).orElse(null);

        return AttendanceRequestResponse.builder()
                .id(req.getId())
                .employeeUserId(req.getEmployeeUserId())
                .employeeName(employeeName)
                .employeeEmail(employeeEmail)
                .departmentName(departmentName)
                .requestType(req.getRequestType())
                .requestDate(req.getRequestDate())
                .partialDayHours(req.getPartialDayHours())
                .partialDayMode(req.getPartialDayMode())
                .reason(req.getReason())
                .status(req.getStatus())
                .assignedApproverId(req.getAssignedApproverId())
                .assignedApproverName(assignedApproverName)
                .notifyUserId(req.getNotifyUserId())
                .notifyUserName(notifyUserName)
                .reviewedByName(reviewerName)
                .reviewedAt(req.getReviewedAt())
                .reviewComment(req.getReviewComment())
                .createdAt(req.getCreatedAt())
                .build();
    }
}
