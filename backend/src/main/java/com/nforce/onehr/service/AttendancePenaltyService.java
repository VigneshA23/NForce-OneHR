package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.AttendancePenaltyResponse;
import com.nforce.onehr.dto.attendance.PenaltyCancelResultResponse;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.AttendancePenaltySpecifications;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
public class AttendancePenaltyService {

    // Statuses a regularization request being in blocks the corresponding penalty from view/action.
    private static final Set<String> ACTIVE_REGULARIZATION_STATUSES = Set.of("PENDING", "PARTIALLY_APPROVED", "APPROVED");
    private static final Set<String> CANCELLABLE_STATUSES =
            Set.of(AttendancePenaltyStatus.PENDING_REVIEW, AttendancePenaltyStatus.APPLIED);

    private final AttendancePenaltyRepository attendancePenaltyRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final RegularizationRequestRepository regularizationRequestRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<AttendancePenaltyResponse> list(String managerEmail, LocalDate from, LocalDate to, String status,
                                                 String discrepancyType, String department, String location, String search) {
        Employee manager = resolveEmployee(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, Employee> employeesById = employeeRepository.findAllByIdWithScheduleDetails(reportIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Function.identity()));

        // department/location/search narrow the manager-scoped id set in Java first — team-sized
        // (bounded by direct-report count), same pattern as EmployeeAssignmentService.listTeamAssignments
        // — before the mandatory manager-scope Specification enforces it at the DB layer.
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

    @Transactional
    public PenaltyCancelResultResponse cancelBulk(String managerEmail, List<UUID> penaltyIds, String reason) {
        Employee manager = resolveEmployee(managerEmail);
        // Snapshot the manager's current direct reports once for the whole batch — each item
        // still gets its own exists/status/regularization re-check below.
        Set<UUID> currentReportIds = new HashSet<>(managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId()));

        List<UUID> succeeded = new ArrayList<>();
        List<PenaltyCancelResultResponse.BulkFailureDto> failed = new ArrayList<>();
        for (UUID penaltyId : penaltyIds) {
            try {
                cancelOne(penaltyId, currentReportIds, manager.getUserId(), reason);
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
    private void cancelOne(UUID penaltyId, Set<UUID> currentReportIds, UUID actorId, String reason) {
        AttendancePenalty penalty = attendancePenaltyRepository.findById(penaltyId)
                .orElseThrow(() -> new IllegalArgumentException("Penalty not found"));
        if (!currentReportIds.contains(penalty.getEmployeeUserId())) {
            throw new AccessDeniedException("Employee is no longer your direct report");
        }
        if (!CANCELLABLE_STATUSES.contains(penalty.getStatus())) {
            throw new IllegalStateException("Only a pending-review or applied penalty can be cancelled");
        }
        boolean hasActiveRegularization = regularizationRequestRepository
                .findByEmployeeUserIdInAndAttendanceDateBetween(
                        List.of(penalty.getEmployeeUserId()), penalty.getIncidentDate(), penalty.getIncidentDate())
                .stream()
                .anyMatch(r -> ACTIVE_REGULARIZATION_STATUSES.contains(r.getStatus()));
        if (hasActiveRegularization) {
            throw new IllegalStateException("A pending or approved regularization exists for this date");
        }

        String before = penalty.getStatus();
        penalty.setStatus(AttendancePenaltyStatus.CANCELLED);
        penalty.setCancelledBy(actorId);
        penalty.setCancelledAt(LocalDateTime.now());
        penalty.setCancellationReason(reason);
        attendancePenaltyRepository.save(penalty);

        auditService.log(actorId, "ATTENDANCE_PENALTY_CANCELLED", penalty.getId(), before, AttendancePenaltyStatus.CANCELLED);
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
