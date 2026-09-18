package com.nforce.onehr.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.attendance.AttendancePenaltyResponse;
import com.nforce.onehr.dto.attendance.PenaltyCancelResultResponse;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.AttendancePenaltySpecifications;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manager: Regularize & Cancel Penalties. {@link #list} returns whatever {@code ExceptionService}
 * has produced via {@link AttendancePenaltyEvaluationService} — an empty list is expected and
 * correct whenever no configured Penalization Policy section matches anything in range, not a bug.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendancePenaltyService {

    // Statuses a regularization request being in blocks the corresponding penalty from view/action.
    private static final Set<String> ACTIVE_REGULARIZATION_STATUSES = Set.of("PENDING", "PARTIALLY_APPROVED", "APPROVED");
    private static final Set<String> CANCELLABLE_STATUSES = Set.of(AttendancePenaltyStatus.PENDING_REVIEW);
    // Same convention as ExceptionService.HR_ROLES: HR_ADMIN/SUPER_ADMIN get organization-wide
    // scope, never the "direct reports" restriction a plain Manager is subject to. Before this,
    // list()/cancelBulk() unconditionally scoped to managerHistoryRepository.findCurrentDirectReportIds,
    // so an HR_ADMIN/SUPER_ADMIN with no direct reports of their own — the common case — silently
    // saw an empty penalty list and could cancel nothing, despite being authorized at the controller.
    private static final Set<String> HR_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final DateTimeFormatter NOTIFICATION_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final AttendancePenaltyRepository attendancePenaltyRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final RegularizationRequestRepository regularizationRequestRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final NotificationService notificationService;
    private final EmployeeService employeeService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<AttendancePenaltyResponse> list(String actorEmail, LocalDate from, LocalDate to, String status,
                                                 String discrepancyType, String department, String location, String search) {
        Employee actor = resolveEmployee(actorEmail);
        List<UUID> reportIds = new ArrayList<>(resolveScopeIds(actor));
        if (reportIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, Employee> employeesById = employeeRepository.findAllByIdWithScheduleDetails(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));

        // department/location/search narrow the scoped id set in Java first — team-sized for a
        // Manager, org-sized for HR_ADMIN/SUPER_ADMIN, same pattern as
        // EmployeeAssignmentService.listTeamAssignments — before the mandatory scope Specification
        // enforces it at the DB layer.
        String q = search != null ? search.trim().toLowerCase() : null;
        List<UUID> scopedIds = employeesById.values().stream()
                .filter(e -> department == null || department.isBlank()
                        || (e.getDepartment() != null && department.equalsIgnoreCase(e.getDepartment().getName())))
                .filter(e -> location == null || location.isBlank()
                        || (e.getLocation() != null && location.equalsIgnoreCase(e.getLocation().getName())))
                .filter(e -> q == null || q.isBlank()
                        || e.getFullName().toLowerCase().contains(q) || e.getEmployeeCode().toLowerCase().contains(q))
                .map(Employee::getUserId)
                .toList();
        if (scopedIds.isEmpty()) {
            return List.of();
        }

        Specification<AttendancePenalty> spec = Specification
                .where(AttendancePenaltySpecifications.employeeUserIdIn(scopedIds))
                .and(AttendancePenaltySpecifications.incidentDateBetween(from, to))
                .and(AttendancePenaltySpecifications.statusEquals(status))
                .and(AttendancePenaltySpecifications.discrepancyTypeEquals(discrepancyType));
        List<AttendancePenalty> penalties = attendancePenaltyRepository.findAll(spec);
        if (penalties.isEmpty()) {
            return List.of();
        }

        // Bulk cross-reference against active regularizations — one query for the whole scoped
        // range, not one lookup per penalty row.
        Set<String> activeRegularizationKeys = activeRegularizationKeys(reportIds, from, to);

        return penalties.stream()
                .filter(p -> !activeRegularizationKeys.contains(regularizationKey(p.getEmployeeUserId(), p.getIncidentDate())))
                .map(p -> toResponse(p, employeesById.get(p.getEmployeeUserId())))
                .sorted(Comparator.comparing(AttendancePenaltyResponse::getIncidentDate).reversed())
                .toList();
    }

    /**
     * HR_ADMIN/SUPER_ADMIN see every employee ever eligible to be penalized (the same population
     * {@code ExceptionService}/the scheduled evaluator actually evaluates — see
     * {@code UserRepository#findEmployeeRoleUserIds}); a plain Manager is still restricted to
     * {@code findCurrentDirectReportIds}. Resolved from the caller's roles only, never client input.
     */
    private Set<UUID> resolveScopeIds(Employee actor) {
        Set<String> roleCodes = actor.getUser() != null
                ? actor.getUser().getRoles().stream().map(Role::getCode).collect(Collectors.toSet())
                : Set.of();
        if (roleCodes.stream().anyMatch(HR_ROLES::contains)) {
            return userRepository.findEmployeeRoleUserIds();
        }
        return new HashSet<>(managerHistoryRepository.findCurrentDirectReportIds(actor.getUserId()));
    }

    @Transactional
    public PenaltyCancelResultResponse cancelBulk(String actorEmail, List<UUID> penaltyIds, String reason) {
        Employee actor = resolveEmployee(actorEmail);
        // Snapshot the actor's scope once for the whole batch — org-wide for HR_ADMIN/SUPER_ADMIN,
        // current direct reports for a Manager. Each item still gets its own exists/status/
        // regularization re-check below.
        Set<UUID> scopeIds = resolveScopeIds(actor);

        List<UUID> succeeded = new ArrayList<>();
        List<PenaltyCancelResultResponse.BulkFailureDto> failed = new ArrayList<>();
        for (UUID penaltyId : penaltyIds) {
            try {
                cancelOne(penaltyId, scopeIds, actor.getUserId(), reason);
                succeeded.add(penaltyId);
            } catch (Exception e) {
                failed.add(PenaltyCancelResultResponse.BulkFailureDto.builder()
                        .id(penaltyId).reason(e.getMessage()).build());
            }
        }
        return PenaltyCancelResultResponse.builder().succeededIds(succeeded).failed(failed).build();
    }

    /**
     * Every eligibility rule is re-checked here against current data, never against whatever the
     * frontend displayed — the frontend's "cancellable" flag is a UI convenience only.
     */
    private void cancelOne(UUID penaltyId, Set<UUID> scopeIds, UUID actorId, String reason) {
        AttendancePenalty penalty = attendancePenaltyRepository.findById(penaltyId)
                .orElseThrow(() -> new IllegalArgumentException("Penalty not found"));
        if (!scopeIds.contains(penalty.getEmployeeUserId())) {
            throw new AccessDeniedException("Employee is no longer your direct report");
        }
        if (!CANCELLABLE_STATUSES.contains(penalty.getStatus())) {
            throw new IllegalStateException("Only a pending-review penalty can be cancelled");
        }
        boolean hasActiveRegularization = regularizationRequestRepository
                .findByEmployeeUserIdInAndAttendanceDateBetween(
                        List.of(penalty.getEmployeeUserId()), penalty.getIncidentDate(), penalty.getIncidentDate())
                .stream()
                .anyMatch(r -> ACTIVE_REGULARIZATION_STATUSES.contains(r.getStatus()));
        if (hasActiveRegularization) {
            throw new IllegalStateException("A pending or approved regularization exists for this date");
        }

        finalizeReversal(penalty, AttendancePenaltyStatus.CANCELLED, actorId, reason, "ATTENDANCE_PENALTY_CANCELLED");
    }

    /**
     * Gap-033/034: the reversal building block {@code ExceptionService}'s shared re-evaluation
     * engine calls once it has determined a specific penalty is no longer justified by corrected
     * attendance/leave data — the automatic counterpart to the manager's manual {@link #cancelOne}
     * action. Takes a single penalty id rather than an employee+date so the caller controls
     * exactly which penalties get reversed (e.g. only the discrepancy types a regularization or
     * leave approval actually invalidated), never "every penalty for this date" as the old
     * date-scoped {@code reverseForApprovedRegularization} did. A penalty that's already
     * CANCELLED/REVERSED, or not found, is silently a no-op — the caller doesn't need to
     * distinguish "already handled" from "handled by us."
     */
    @Transactional
    public void reverseIfActive(UUID penaltyId, UUID actorId, String reason, String auditAction) {
        attendancePenaltyRepository.findById(penaltyId).ifPresent(penalty -> {
            if (CANCELLABLE_STATUSES.contains(penalty.getStatus())) {
                finalizeReversal(penalty, AttendancePenaltyStatus.REVERSED, actorId, reason, auditAction);
            }
        });
    }

    /**
     * Section 17/18: the one place a penalty's leave/LOP impact is undone, shared by both the
     * manual CANCELLED path and the automatic REVERSED path above — the two differ only in status
     * value and audit action, never in what "undo this penalty" actually does.
     *
     * <p>Race-safe by construction, not just by convention: the status transition itself is a
     * single conditional {@code UPDATE ... WHERE status IN (...)} ({@link AttendancePenaltyRepository#transitionStatus}),
     * so two concurrent calls on the same penalty (one manual cancel racing one automatic
     * regularization-reversal, or two of either) can never both succeed — whichever commits
     * second affects zero rows and returns early before ever touching the leave balance, audit
     * log, or notifications a second time. This replaces relying on {@code @Transactional} alone,
     * which does not by itself prevent two transactions from each reading the same
     * pre-transition status before either commits.
     */
    private void finalizeReversal(AttendancePenalty penalty, String newStatus, UUID actorId, String reason, String auditAction) {
        String before = penalty.getStatus();
        LocalDateTime now = LocalDateTime.now();
        int updated = attendancePenaltyRepository.transitionStatus(penalty.getId(), newStatus, actorId, now, reason, CANCELLABLE_STATUSES);
        if (updated == 0) {
            log.debug("Penalty {} was already transitioned out of {} by a concurrent call — skipping duplicate reversal.",
                    penalty.getId(), CANCELLABLE_STATUSES);
            return;
        }
        // Keep the in-memory entity consistent with what the conditional UPDATE above just
        // committed — the JPQL bulk update bypasses the persistence context, so these fields
        // would otherwise still read their pre-transition values for the rest of this method
        // (and for any caller — e.g. cancelBulk's per-item response — that inspects `penalty` afterward).
        penalty.setStatus(newStatus);
        penalty.setCancelledBy(actorId);
        penalty.setCancelledAt(now);
        penalty.setCancellationReason(reason);

        Map<String, BigDecimal> restoredLeave = restoreLeaveBalance(penalty);

        // Gap-038: before/after now carries what actually happened, not just the status strings —
        // which leave types/amounts (if any) were credited back, and the LOP originally reversed,
        // so "why was this restored, and by how much" is answerable from the audit log alone.
        Map<String, Object> afterSnapshot = new LinkedHashMap<>();
        afterSnapshot.put("status", newStatus);
        afterSnapshot.put("reason", reason);
        if (restoredLeave != null && !restoredLeave.isEmpty()) {
            afterSnapshot.put("leaveRestored", restoredLeave);
        }
        if (penalty.getLopDays() != null && penalty.getLopDays().signum() > 0) {
            afterSnapshot.put("lopReversed", penalty.getLopDays());
        }
        auditService.log(actorId, auditAction, penalty.getId(), before, auditSnapshot.toJson(afterSnapshot));
        notifyPenaltyReversed(penalty, newStatus, reason);
    }

    /**
     * The exact inverse of {@code PenaltyDeductionService#apply}'s PAID_LEAVE branch: restores
     * each leave type's {@code usedDays} by exactly the amount recorded in {@code leaveBreakdown}
     * at the time this penalty was applied — never more, regardless of what the balance's current
     * used-days happens to be. A LOSS_OF_PAY penalty (no breakdown) or one predating this column
     * is a no-op. {@code max(ZERO)} is a defensive floor only — it should never actually trigger,
     * since a balance can never have been debited by more than it can now be credited back.
     *
     * @return exactly what was credited back, by leave type code — Gap-038's audit detail reads
     * this same map rather than re-deriving it, so the audit trail can never disagree with what
     * was actually restored. Empty (never null) when there was nothing to restore.
     */
    private Map<String, BigDecimal> restoreLeaveBalance(AttendancePenalty penalty) {
        if (penalty.getLeaveBreakdown() == null || penalty.getLeaveBreakdown().isBlank()) {
            return Map.of();
        }
        Map<String, BigDecimal> breakdown;
        try {
            breakdown = objectMapper.readValue(penalty.getLeaveBreakdown(), new TypeReference<Map<String, BigDecimal>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse leaveBreakdown for penalty {} — leave balance not restored: {}",
                    penalty.getId(), e.getMessage());
            return Map.of();
        }
        int year = penalty.getIncidentDate().getYear();
        Map<String, BigDecimal> restored = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : breakdown.entrySet()) {
            LeaveType type = leaveTypeRepository.findByCode(entry.getKey()).orElse(null);
            if (type == null) {
                continue;
            }
            leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(penalty.getEmployeeUserId(), type.getId(), year)
                    .ifPresent(balance -> {
                        balance.setUsedDays(balance.getUsedDays().subtract(entry.getValue()).max(BigDecimal.ZERO));
                        leaveBalanceRepository.save(balance);
                        restored.put(entry.getKey(), entry.getValue());
                    });
        }
        return restored;
    }

    private void notifyPenaltyReversed(AttendancePenalty penalty, String newStatus, String reason) {
        boolean cancelled = AttendancePenaltyStatus.CANCELLED.equals(newStatus);
        String title = "Attendance Penalty " + (cancelled ? "Cancelled" : "Reversed");
        StringBuilder message = new StringBuilder("Your attendance penalty of ")
                .append(penalty.getDeductionDays() != null ? penalty.getDeductionDays() : BigDecimal.ZERO)
                .append(" day(s) for ").append(penalty.getIncidentDate().format(NOTIFICATION_DATE_FMT))
                .append(" has been ").append(cancelled ? "cancelled" : "reversed").append('.');
        if (reason != null && !reason.isBlank()) {
            message.append(" Reason: ").append(reason.trim());
        }
        if (penalty.getLeaveDeductionDays() != null && penalty.getLeaveDeductionDays().signum() > 0) {
            message.append(" ").append(penalty.getLeaveDeductionDays()).append(" day(s) of leave balance previously deducted has been restored.");
        }
        notificationService.send(penalty.getEmployeeUserId(), "ATTENDANCE_PENALTY_" + newStatus, title, message.toString(), "/attendance");

        EmployeeResponse.ManagerRef manager = employeeService.findCurrentManagersBulk(List.of(penalty.getEmployeeUserId()))
                .get(penalty.getEmployeeUserId());
        if (manager != null) {
            notificationService.send(UUID.fromString(manager.getUserId()), "ATTENDANCE_PENALTY_" + newStatus, title,
                    "A team member's attendance penalty for " + penalty.getIncidentDate().format(NOTIFICATION_DATE_FMT)
                            + " has been " + (cancelled ? "cancelled" : "reversed") + ".",
                    "/my-team");
        }
    }

    private Set<String> activeRegularizationKeys(List<UUID> employeeIds, LocalDate from, LocalDate to) {
        return regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(employeeIds, from, to).stream()
                .filter(r -> ACTIVE_REGULARIZATION_STATUSES.contains(r.getStatus()))
                .map(r -> regularizationKey(r.getEmployeeUserId(), r.getAttendanceDate()))
                .collect(Collectors.toSet());
    }

    private String regularizationKey(UUID employeeUserId, LocalDate date) {
        return employeeUserId + "|" + date;
    }

    private AttendancePenaltyResponse toResponse(AttendancePenalty penalty, Employee employee) {
        return AttendancePenaltyResponse.builder()
                .id(penalty.getId())
                .employeeUserId(penalty.getEmployeeUserId())
                .fullName(employee != null ? employee.getFullName() : null)
                .employeeCode(employee != null ? employee.getEmployeeCode() : null)
                .incidentDate(penalty.getIncidentDate())
                .penalizedOn(penalty.getPenalizedOn())
                .status(penalty.getStatus())
                .locationName(employee != null && employee.getLocation() != null ? employee.getLocation().getName() : null)
                .departmentName(employee != null && employee.getDepartment() != null ? employee.getDepartment().getName() : null)
                .discrepancyType(penalty.getDiscrepancyType())
                .deductionDays(penalty.getDeductionDays())
                .cancellable(CANCELLABLE_STATUSES.contains(penalty.getStatus()))
                .build();
    }

    private Employee resolveEmployee(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }
}
