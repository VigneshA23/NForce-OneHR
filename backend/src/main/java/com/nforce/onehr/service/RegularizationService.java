package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.CreateRegularizationRequest;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.entity.AttendanceRecord;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.RegularizationRequest;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRecordRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Attendance Regularization: employee-submitted corrections for missed/wrong punches,
 * routed via EmployeeManagerHistory to the employee's current manager (or HR/Super Admin)
 * for approval. Approval upserts the corresponding attendance_records row.
 *
 * Notification on approve/reject is intentionally NOT wired here — owned by another
 * workstream. Hook it in at the two TODO(notifications) call sites once that service exists.
 */
@Service
@RequiredArgsConstructor
public class RegularizationService {

    private static final Set<String> APPROVER_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final RegularizationRequestRepository regularizationRepository;
    private final AttendanceRecordRepository attendanceRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditService auditService;

    // Lookback window (N days): how far back an employee may request a correction.
    // OPEN QUESTION FOR PRODUCT OWNER — is 30 days the right default, and should it
    // vary by role/department? Kept configurable (app.attendance.regularization.lookback-days)
    // rather than hardcoded, pending that decision.
    @Value("${app.attendance.regularization.lookback-days:30}")
    private int lookbackDays;

    @Transactional
    public RegularizationResponse submit(CreateRegularizationRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);

        if (req.getRequestedCheckIn() == null && req.getRequestedCheckOut() == null) {
            throw new IllegalArgumentException("Provide at least a corrected check-in or check-out time");
        }
        if (req.getRequestedCheckIn() != null && !req.getRequestedCheckIn().toLocalDate().equals(req.getAttendanceDate())) {
            throw new IllegalArgumentException("Corrected check-in time must fall on the attendance date");
        }
        if (req.getRequestedCheckOut() != null && !req.getRequestedCheckOut().toLocalDate().equals(req.getAttendanceDate())) {
            throw new IllegalArgumentException("Corrected check-out time must fall on the attendance date");
        }
        if (req.getRequestedCheckIn() != null && req.getRequestedCheckOut() != null
                && !req.getRequestedCheckOut().isAfter(req.getRequestedCheckIn())) {
            throw new IllegalArgumentException("Check-out time must be after check-in time");
        }
        validateLookbackWindow(req.getAttendanceDate());

        if (regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(
                actor.getId(), req.getAttendanceDate(), "PENDING")) {
            throw new IllegalArgumentException("A pending regularization request already exists for this date");
        }

        RegularizationRequest entity = RegularizationRequest.builder()
                .employeeUserId(actor.getId())
                .attendanceDate(req.getAttendanceDate())
                .requestedCheckIn(req.getRequestedCheckIn())
                .requestedCheckOut(req.getRequestedCheckOut())
                .reason(req.getReason().trim())
                .status("PENDING")
                .build();
        entity = regularizationRepository.save(entity);

        auditService.log(actor.getId(), "REGULARIZATION_REQUESTED", actor.getId());
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public List<RegularizationResponse> listMine(String actorEmail) {
        User actor = requireActor(actorEmail);
        return regularizationRepository.findByEmployeeUserIdOrderByCreatedAtDesc(actor.getId())
                .stream().map(this::toResponse).toList();
    }

    /** Manager sees only their direct reports' pending requests; HR/Super Admin see all. */
    @Transactional(readOnly = true)
    public List<RegularizationResponse> listPendingForApprover(String actorEmail) {
        User actor = requireActor(actorEmail);
        List<RegularizationRequest> pending = regularizationRepository.findByStatus("PENDING");

        if (hasOverrideRole(actor)) {
            return pending.stream().map(this::toResponse).toList();
        }
        return pending.stream()
                .filter(r -> isCurrentManagerOf(actor.getId(), r.getEmployeeUserId()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public RegularizationResponse approve(UUID requestId, String actorEmail) {
        User actor = requireActor(actorEmail);
        RegularizationRequest req = requirePending(requestId);
        assertCanReview(req, actor);

        AttendanceRecord record = attendanceRepository
                .findByUserIdAndAttendanceDate(req.getEmployeeUserId(), req.getAttendanceDate())
                .orElseGet(() -> AttendanceRecord.builder()
                        .userId(req.getEmployeeUserId())
                        .attendanceDate(req.getAttendanceDate())
                        .build());
        if (req.getRequestedCheckIn() != null) record.setCheckIn(req.getRequestedCheckIn());
        if (req.getRequestedCheckOut() != null) record.setCheckOut(req.getRequestedCheckOut());
        record.setStatus("PRESENT");
        record.setSource("REGULARIZATION");
        attendanceRepository.save(record);

        req.setStatus("APPROVED");
        req.setReviewedBy(actor.getId());
        req.setReviewedAt(LocalDateTime.now());
        regularizationRepository.save(req);

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

        auditService.log(actor.getId(), "REGULARIZATION_REJECTED", req.getEmployeeUserId());
        // TODO(notifications): notify req.getEmployeeUserId() of rejection — owned by another workstream.
        return toResponse(req);
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
        String employeeName = employeeRepository.findById(req.getEmployeeUserId())
                .map(Employee::getFullName)
                .orElse("Unknown");
        String employeeEmail = userRepository.findById(req.getEmployeeUserId())
                .map(User::getEmail).orElse("");
        String reviewerName = req.getReviewedBy() == null ? null
                : employeeRepository.findById(req.getReviewedBy()).map(Employee::getFullName).orElse(null);

        return RegularizationResponse.builder()
                .id(req.getId())
                .employeeUserId(req.getEmployeeUserId())
                .employeeName(employeeName)
                .employeeEmail(employeeEmail)
                .attendanceDate(req.getAttendanceDate())
                .requestedCheckIn(req.getRequestedCheckIn())
                .requestedCheckOut(req.getRequestedCheckOut())
                .reason(req.getReason())
                .status(req.getStatus())
                .reviewedByName(reviewerName)
                .reviewedAt(req.getReviewedAt())
                .reviewComment(req.getReviewComment())
                .createdAt(req.getCreatedAt())
                .build();
    }
}
