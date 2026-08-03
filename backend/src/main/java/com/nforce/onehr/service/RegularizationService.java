package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.ApprovalHistoryEntryDto;
import com.nforce.onehr.dto.attendance.ApproverOptionDto;
import com.nforce.onehr.dto.attendance.CreateRegularizationRequest;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.RegularizationApproval;
import com.nforce.onehr.entity.RegularizationRequest;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.RegularizationApprovalRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Attendance Regularization: employee-submitted corrections for missed/wrong punches,
 * routed via EmployeeManagerHistory to the employee's current manager (or HR/Super Admin)
 * for approval. Approval upserts the corresponding attendance_records row — the same table
 * and entity {@link AttendanceService} writes on check-in/check-out — tagging it with
 * source=REGULARIZATION.
 *
 * Notification on approve/reject is intentionally NOT wired here — owned by another
 * workstream. Hook it in at the two TODO(notifications) call sites once that service exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegularizationService {

    private static final Set<String> APPROVER_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    // Super Admin deliberately excluded — they already have blanket review visibility via
    // APPROVER_OVERRIDE_ROLES above, so they're not offered as an explicit "Assign To" target.
    private static final Set<String> ELIGIBLE_APPROVER_ROLES = Set.of("MANAGER", "HR_ADMIN");
    private static final String STATUS_PRESENT = "PRESENT";
    private static final String STATUS_LATE = "LATE";
    private static final String STATUS_HALF_DAY = "HALF_DAY";
    private static final String SOURCE_REGULARIZATION = "REGULARIZATION";

    private final RegularizationRequestRepository regularizationRepository;
    private final RegularizationApprovalRepository regularizationApprovalRepository;
    private final AttendanceRepository attendanceRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;
    private final AttendanceProperties attendanceProps;

    /** Resolved requested times after applying punch auto-fill from attendance history. */
    private record ResolvedTimes(LocalDateTime checkIn, LocalDateTime checkOut) {}

    // Lookback window (N days): how far back an employee may request a correction.
    // OPEN QUESTION FOR PRODUCT OWNER — is 30 days the right default, and should it
    // vary by role/department? Kept configurable (app.attendance.regularization.lookback-days)
    // rather than hardcoded, pending that decision.
    @Value("${app.attendance.regularization.lookback-days:30}")
    private int lookbackDays;

    @Transactional
    public RegularizationResponse submit(CreateRegularizationRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);

        ResolvedTimes times = resolveTimes(req, actor.getId());
        validateLookbackWindow(req.getAttendanceDate());

        if (regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(
                actor.getId(), req.getAttendanceDate(), "PENDING")) {
            throw new IllegalArgumentException("A pending regularization request already exists for this date");
        }

        RegularizationRequest entity = RegularizationRequest.builder()
                .employeeUserId(actor.getId())
                .assignedApproverId(resolveAssignedApprover(actor.getId(), req.getManagerUserId()))
                .attendanceDate(req.getAttendanceDate())
                .requestedCheckIn(times.checkIn())
                .requestedCheckOut(times.checkOut())
                .reason(req.getReason().trim())
                .status("PENDING")
                .build();
        entity = regularizationRepository.save(entity);

        auditService.log(actor.getId(), "REGULARIZATION_REQUESTED", actor.getId());
        return toResponse(entity);
    }

    /**
     * Edit a still-pending request. Only the submitting employee may edit, and only while
     * status is PENDING — approved/rejected requests are immutable history.
     */
    @Transactional
    public RegularizationResponse update(UUID requestId, CreateRegularizationRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        RegularizationRequest existing = regularizationRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (!existing.getEmployeeUserId().equals(actor.getId())) {
            throw new AccessDeniedException("You can only edit your own requests");
        }
        if (!"PENDING".equals(existing.getStatus())) {
            throw new IllegalStateException("Only pending requests can be edited");
        }

        ResolvedTimes times = resolveTimes(req, actor.getId());
        validateLookbackWindow(req.getAttendanceDate());

        if (!existing.getAttendanceDate().equals(req.getAttendanceDate())
                && regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(
                        actor.getId(), req.getAttendanceDate(), "PENDING")) {
            throw new IllegalArgumentException("A pending regularization request already exists for this date");
        }

        existing.setAttendanceDate(req.getAttendanceDate());
        existing.setRequestedCheckIn(times.checkIn());
        existing.setRequestedCheckOut(times.checkOut());
        existing.setReason(req.getReason().trim());
        existing.setAssignedApproverId(resolveAssignedApprover(actor.getId(), req.getManagerUserId()));
        existing = regularizationRepository.save(existing);

        auditService.log(actor.getId(), "REGULARIZATION_UPDATED", existing.getId());
        return toResponse(existing);
    }

    /** Validates the raw request, then fills any omitted side from the existing punch record. */
    private ResolvedTimes resolveTimes(CreateRegularizationRequest req, UUID employeeId) {
        if (req.getRequestedCheckIn() == null && req.getRequestedCheckOut() == null) {
            throw new IllegalArgumentException("Provide at least a corrected check-in or check-out time");
        }
        if (req.getRequestedCheckIn() != null && !req.getRequestedCheckIn().toLocalDate().equals(req.getAttendanceDate())) {
            throw new IllegalArgumentException("Corrected check-in time must fall on the attendance date");
        }
        if (req.getRequestedCheckOut() != null && !req.getRequestedCheckOut().toLocalDate().equals(req.getAttendanceDate())) {
            throw new IllegalArgumentException("Corrected check-out time must fall on the attendance date");
        }

        Attendance existingPunch;
        try {
            existingPunch = attendanceRepository
                    .findByEmployeeUserIdAndWorkDate(employeeId, req.getAttendanceDate())
                    .orElse(null);
        } catch (Exception e) {
            // Punch auto-fill is a convenience, not a requirement — if the attendance lookup
            // is unavailable for any reason, fall back to exactly what the caller provided
            // rather than failing the whole regularization request over it.
            log.warn("Punch auto-fill lookup failed for employee {} on {}; continuing without it: {}",
                    employeeId, req.getAttendanceDate(), e.getMessage());
            existingPunch = null;
        }
        LocalDateTime checkIn = req.getRequestedCheckIn() != null
                ? req.getRequestedCheckIn()
                : (existingPunch != null ? existingPunch.getCheckInAt() : null);
        LocalDateTime checkOut = req.getRequestedCheckOut() != null
                ? req.getRequestedCheckOut()
                : (existingPunch != null ? existingPunch.getCheckOutAt() : null);

        if (checkIn != null && checkOut != null && !checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("Check-out time must be after check-in time");
        }
        return new ResolvedTimes(checkIn, checkOut);
    }

    /** Selected manager (validated as an eligible approver) else the employee's current manager. */
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

    /** Active users eligible to be selected/assigned as an approver, for the manager-select dropdown. */
    @Transactional(readOnly = true)
    public List<ApproverOptionDto> listApprovers() {
        return employeeRepository.findActiveByRoleCodes(ELIGIBLE_APPROVER_ROLES).stream()
                .map(e -> ApproverOptionDto.builder()
                        .userId(e.getUserId())
                        .fullName(e.getFullName())
                        .email(e.getUser().getEmail())
                        .roleCode(e.getUser().getRoles().stream()
                                .map(Role::getCode)
                                .filter(ELIGIBLE_APPROVER_ROLES::contains)
                                .findFirst().orElse(""))
                        .build())
                .sorted(Comparator.comparing(ApproverOptionDto::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /** Super Admin: full history across everyone, with optional filters. */
    @Transactional(readOnly = true)
    public List<RegularizationResponse> listAll(UUID employeeUserId, UUID approverUserId,
                                                 UUID departmentId, String month, String status) {
        return regularizationRepository.findAll().stream()
                .filter(r -> employeeUserId == null || employeeUserId.equals(r.getEmployeeUserId()))
                .filter(r -> approverUserId == null || approverUserId.equals(r.getAssignedApproverId()))
                .filter(r -> status == null || status.equalsIgnoreCase(r.getStatus()))
                .filter(r -> month == null || r.getAttendanceDate().toString().startsWith(month))
                .filter(r -> departmentId == null || departmentId.equals(departmentIdOf(r.getEmployeeUserId())))
                .sorted(Comparator.comparing(RegularizationRequest::getCreatedAt).reversed())
                .map(this::toResponse)
                .toList();
    }

    private UUID departmentIdOf(UUID employeeUserId) {
        return employeeRepository.findById(employeeUserId)
                .map(Employee::getDepartment)
                .map(d -> d.getId())
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<RegularizationResponse> listMine(String actorEmail) {
        User actor = requireActor(actorEmail);
        return regularizationRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId())
                .stream().map(this::toResponse).toList();
    }

    /** Manager sees only requests assigned to them; HR/Super Admin see all pending requests. */
    @Transactional(readOnly = true)
    public List<RegularizationResponse> listPendingForApprover(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<RegularizationRequest> pending = regularizationRepository.findByStatus("PENDING");

        if (hasOverrideRole(actor)) {
            return pending.stream().map(this::toResponse).toList();
        }
        return pending.stream()
                .filter(r -> actor.getId().equals(r.getAssignedApproverId()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public RegularizationResponse approve(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        RegularizationRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        Attendance record = attendanceRepository
                .findByEmployeeUserIdAndWorkDate(req.getEmployeeUserId(), req.getAttendanceDate())
                .orElse(null);
        if (record == null && req.getRequestedCheckIn() == null) {
            // check_in_at is mandatory on a new row (one-pair-per-day punch schema) — a
            // checkout-only correction can only ever amend an existing punch.
            throw new IllegalArgumentException(
                    "Cannot approve: no attendance record exists for this date and no check-in time was requested");
        }
        if (record == null) {
            record = Attendance.builder()
                    .employeeUserId(req.getEmployeeUserId())
                    .workDate(req.getAttendanceDate())
                    .checkInAt(req.getRequestedCheckIn())
                    .build();
        }
        if (req.getRequestedCheckIn() != null) record.setCheckInAt(req.getRequestedCheckIn());
        if (req.getRequestedCheckOut() != null) record.setCheckOutAt(req.getRequestedCheckOut());
        record.setSource(SOURCE_REGULARIZATION);
        recomputeDerivedFields(record);
        attendanceRepository.save(record);

        req.setStatus("APPROVED");
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        regularizationRepository.save(req);
        recordApproval(req.getId(), actor.getId(), "APPROVED", comment);

        auditService.log(actor.getId(), "REGULARIZATION_APPROVED", req.getEmployeeUserId());
        // TODO(notifications): notify req.getEmployeeUserId() of approval — owned by another workstream.
        return toResponse(req);
    }

    @Transactional
    public RegularizationResponse reject(UUID requestId, String comment, String actorEmail) {
        User actor = requireActor(actorEmail);
        RegularizationRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        req.setStatus("REJECTED");
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        req.setReviewComment(comment);
        regularizationRepository.save(req);
        recordApproval(req.getId(), actor.getId(), "REJECTED", comment);

        auditService.log(actor.getId(), "REGULARIZATION_REJECTED", req.getEmployeeUserId());
        // TODO(notifications): notify req.getEmployeeUserId() of rejection — owned by another workstream.
        return toResponse(req);
    }

    private void recordApproval(UUID requestId, UUID actionBy, String actionType, String comments) {
        regularizationApprovalRepository.save(RegularizationApproval.builder()
                .requestId(requestId)
                .actionBy(actionBy)
                .actionType(actionType)
                .comments(comments)
                .build());
    }

    /** Mirrors AttendanceService's check-in/check-out status derivation for a corrected row. */
    private void recomputeDerivedFields(Attendance record) {
        LocalTime deadline = attendanceProps.getShiftStart().plusMinutes(attendanceProps.getLateGraceMinutes());
        int lateByMinutes = record.getCheckInAt().toLocalTime().isAfter(deadline)
                ? (int) Duration.between(deadline, record.getCheckInAt().toLocalTime()).toMinutes()
                : 0;
        record.setLateByMinutes(lateByMinutes);

        if (record.getCheckOutAt() == null) {
            record.setWorkedMinutes(null);
            record.setStatus(lateByMinutes > 0 ? STATUS_LATE : STATUS_PRESENT);
            return;
        }

        int workedMinutes = (int) Duration.between(record.getCheckInAt(), record.getCheckOutAt()).toMinutes();
        record.setWorkedMinutes(workedMinutes);
        record.setStatus(workedMinutes < attendanceProps.getHalfDayMaxHours() * 60
                ? STATUS_HALF_DAY
                : (lateByMinutes > 0 ? STATUS_LATE : STATUS_PRESENT));
    }

    private RegularizationRequest requirePending(UUID requestId) {
        RegularizationRequest req = regularizationRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        if (!"PENDING".equals(req.getStatus())) {
            throw new IllegalArgumentException("Only pending requests can be reviewed");
        }
        return req;
    }

    private void validateLookbackWindow(LocalDate attendanceDate) {
        LocalDate today = LocalDate.now();
        if (attendanceDate.isAfter(today)) {
            throw new IllegalArgumentException("Cannot request regularization for a future date");
        }
        if (attendanceDate.isBefore(today.minusDays(lookbackDays))) {
            throw new IllegalArgumentException(
                    "Regularization requests are only allowed within the last " + lookbackDays + " days");
        }
    }

    private void assertCanReview(RegularizationRequest req, User actor) {
        if (hasOverrideRole(actor)) return;
        if (actor.getId().equals(req.getAssignedApproverId())) return;

        // Fallback for requests predating assigned_approver_id (should be rare — V34 backfills
        // in-flight PENDING rows at migration time).
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

    private RegularizationResponse toResponse(RegularizationRequest req) {
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
        Long totalMinutes = (req.getRequestedCheckIn() != null && req.getRequestedCheckOut() != null)
                ? Duration.between(req.getRequestedCheckIn(), req.getRequestedCheckOut()).toMinutes()
                : null;
        List<ApprovalHistoryEntryDto> history = regularizationApprovalRepository
                .findByRequestIdOrderByActionDateDesc(req.getId()).stream()
                .map(a -> ApprovalHistoryEntryDto.builder()
                        .actionType(a.getActionType())
                        .actorName(employeeRepository.findById(a.getActionBy())
                                .map(Employee::getFullName).orElse("Unknown"))
                        .comments(a.getComments())
                        .actionDate(a.getActionDate())
                        .build())
                .toList();

        return RegularizationResponse.builder()
                .id(req.getId())
                .employeeUserId(req.getEmployeeUserId())
                .employeeName(employeeName)
                .employeeEmail(employeeEmail)
                .departmentName(departmentName)
                .attendanceDate(req.getAttendanceDate())
                .requestedCheckIn(req.getRequestedCheckIn())
                .requestedCheckOut(req.getRequestedCheckOut())
                .reason(req.getReason())
                .status(req.getStatus())
                .assignedApproverId(req.getAssignedApproverId())
                .assignedApproverName(assignedApproverName)
                .totalMinutes(totalMinutes)
                .reviewedByName(reviewerName)
                .reviewedAt(req.getReviewedAt())
                .reviewComment(req.getReviewComment())
                .createdAt(req.getCreatedAt())
                .approvalHistory(history)
                .build();
    }
}
