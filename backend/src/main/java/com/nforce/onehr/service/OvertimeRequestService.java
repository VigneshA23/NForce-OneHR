package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.CreateOvertimeRequest;
import com.nforce.onehr.dto.attendance.OvertimeRequestResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.OvertimeRequest;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.OvertimeRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Employee-submitted overtime requests. Tracked/visible only — approval never adjusts
 * Attendance.workedMinutes, since there's no shift-end (ONEHR-108 not built) to reconcile
 * overtime against yet.
 *
 * TODO: once ONEHR-108 shift assignment exists, consider surfacing approved overtime minutes
 * in worked-hours reporting.
 *
 * Approver resolution mirrors RegularizationService's two-argument "assign to a chosen approver
 * else fall back to current manager" pattern (see AttendanceRequestService for the same reuse).
 */
@Service
@RequiredArgsConstructor
public class OvertimeRequestService {

    private static final Set<String> APPROVER_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final Set<String> ELIGIBLE_APPROVER_ROLES = Set.of("MANAGER", "HR_ADMIN");
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final DateTimeFormatter NOTIFICATION_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final OvertimeRequestRepository requestRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public OvertimeRequestResponse submit(CreateOvertimeRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);

        if (!req.getRequestedEnd().isAfter(req.getRequestedStart())) {
            throw new IllegalArgumentException("Requested end time must be after the requested start time");
        }
        if (!req.getRequestedStart().toLocalDate().equals(req.getWorkDate())
                && !req.getRequestedEnd().toLocalDate().equals(req.getWorkDate())) {
            throw new IllegalArgumentException("Requested start/end must fall on the work date");
        }
        assertNotBeforeJoiningDate(actor.getId(), req.getWorkDate());
        UUID notifyUserId = resolveNotifyUser(req.getNotifyUserId());

        OvertimeRequest entity = OvertimeRequest.builder()
                .employeeUserId(actor.getId())
                .assignedApproverId(resolveAssignedApprover(actor.getId(), req.getManagerUserId()))
                .workDate(req.getWorkDate())
                .requestedStart(req.getRequestedStart())
                .requestedEnd(req.getRequestedEnd())
                .notifyUserId(notifyUserId)
                .reason(req.getReason().trim())
                .status(STATUS_PENDING)
                .build();
        entity = requestRepository.save(entity);

        auditService.log(actor.getId(), "OVERTIME_REQUESTED", entity.getId());
        if (notifyUserId != null) {
            Employee requester = employeeRepository.findById(actor.getId()).orElse(null);
            String requesterName = requester != null ? requester.getFullName() : "A colleague";
            notificationService.send(notifyUserId, "ATTENDANCE",
                    "Overtime request submitted",
                    requesterName + " submitted an overtime request for " + req.getWorkDate() + " and wanted you to know.",
                    "/attendance");
        }
        return toResponse(entity);
    }

    /**
     * There's no work to claim overtime for on a date before the employee even joined. Mirrors
     * RegularizationService.assertNotBeforeJoiningDate — a data-integrity rule, not a business
     * policy, so it applies to every role with no override. Silently allows when the employee
     * record can't be resolved (never happens for a real actor, but fails open rather than
     * blocking on an unrelated lookup issue).
     */
    private void assertNotBeforeJoiningDate(UUID employeeUserId, LocalDate workDate) {
        LocalDate joiningDate = employeeRepository.findById(employeeUserId).map(Employee::getJoiningDate).orElse(null);
        if (joiningDate != null && workDate.isBefore(joiningDate)) {
            throw new IllegalArgumentException(
                    "Overtime requests cannot be made prior to your joining date (" + joiningDate.format(NOTIFICATION_DATE_FMT) + ").");
        }
    }

    private UUID resolveNotifyUser(UUID notifyUserId) {
        if (notifyUserId == null) return null;
        if (!userRepository.existsById(notifyUserId)) {
            throw new IllegalArgumentException("Selected employee to notify was not found");
        }
        return notifyUserId;
    }

    @Transactional(readOnly = true)
    public List<OvertimeRequestResponse> listMine(String actorEmail) {
        User actor = requireActor(actorEmail);
        return requestRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId())
                .stream().map(this::toResponse).toList();
    }

    /** Manager sees only requests assigned to them; HR/Super Admin see all pending requests. */
    @Transactional(readOnly = true)
    public List<OvertimeRequestResponse> listPendingForApprover(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<OvertimeRequest> pending = requestRepository.findByStatus(STATUS_PENDING);

        if (hasOverrideRole(actor)) {
            return pending.stream().map(this::toResponse).toList();
        }
        return pending.stream()
                .filter(r -> actor.getId().equals(r.getAssignedApproverId()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public OvertimeRequestResponse approve(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        OvertimeRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        req.setStatus(STATUS_APPROVED);
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        requestRepository.save(req);

        auditService.log(actor.getId(), "OVERTIME_APPROVED", req.getEmployeeUserId());
        notificationService.send(req.getEmployeeUserId(), "OVERTIME_APPROVED",
                "Overtime Request Approved",
                "Your overtime request for " + req.getWorkDate() + " has been approved by " + employeeName(actor.getId()) + ".",
                "/requests?type=OVERTIME");
        return toResponse(req);
    }

    @Transactional
    public OvertimeRequestResponse reject(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        OvertimeRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        req.setStatus(STATUS_REJECTED);
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        requestRepository.save(req);

        auditService.log(actor.getId(), "OVERTIME_REJECTED", req.getEmployeeUserId());
        notificationService.send(req.getEmployeeUserId(), "OVERTIME_REJECTED",
                "Overtime Request Rejected",
                "Your overtime request for " + req.getWorkDate() + " has been rejected by " + employeeName(actor.getId())
                        + (comment != null && !comment.isBlank() ? ". Reason: " + comment.trim() : "."),
                "/requests?type=OVERTIME");
        return toResponse(req);
    }

    // ---------------------------------------------------------------- internals

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

    private OvertimeRequest requirePending(UUID requestId) {
        OvertimeRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (!STATUS_PENDING.equals(req.getStatus())) {
            throw new IllegalArgumentException("Only pending requests can be reviewed");
        }
        return req;
    }

    private void assertCanReview(OvertimeRequest req, User actor) {
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

    private OvertimeRequestResponse toResponse(OvertimeRequest req) {
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
        Long requestedMinutes = Duration.between(req.getRequestedStart(), req.getRequestedEnd()).toMinutes();

        return OvertimeRequestResponse.builder()
                .id(req.getId())
                .employeeUserId(req.getEmployeeUserId())
                .employeeName(employeeName)
                .employeeEmail(employeeEmail)
                .departmentName(departmentName)
                .workDate(req.getWorkDate())
                .requestedStart(req.getRequestedStart())
                .requestedEnd(req.getRequestedEnd())
                .requestedMinutes(requestedMinutes)
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
