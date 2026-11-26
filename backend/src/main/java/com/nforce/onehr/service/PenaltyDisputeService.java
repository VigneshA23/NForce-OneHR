package com.nforce.onehr.service;

import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.attendance.DecidePenaltyDisputeRequest;
import com.nforce.onehr.dto.attendance.ForwardPenaltyDisputeRequest;
import com.nforce.onehr.dto.attendance.PenaltyDisputeResponse;
import com.nforce.onehr.dto.attendance.RaisePenaltyDisputeRequest;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyDispute;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AttendancePenaltyDisputeRepository;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PN-02 - employee dispute of an attendance penalty.
 *
 * <p>Lifecycle: the employee raises it while the penalty is {@code PENDING_REVIEW} and its payroll
 * cycle is not yet locked; it goes to the employee's current manager ({@code PENDING_MANAGER}),
 * who forwards it to HR with a recommendation ({@code PENDING_HR}); HR resolves it {@code UPHELD}
 * (penalty stands) or {@code REVERSED}. A reversal goes through
 * {@link AttendancePenaltyService#reverseIfActive}, i.e. exactly the same leave-balance restore,
 * audit and notification path as a manager cancel or a regularization reversal. An employee with
 * no current manager skips straight to HR.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PenaltyDisputeService {

    private static final String ORG_WIDE_AUTHORITY = "attendance:EDIT:ALL";
    private static final String TEAM_AUTHORITY = "attendance:EDIT:TEAM";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final AttendancePenaltyDisputeRepository disputeRepository;
    private final AttendancePenaltyRepository penaltyRepository;
    private final AttendancePenaltyService attendancePenaltyService;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final EmployeeService employeeService;
    private final PayslipRepository payslipRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final EffectivePermissionService effectivePermissionService;

    /** True when the employee's payroll cycle containing {@code incidentDate} is already locked. */
    public boolean isPayrollLocked(UUID employeeUserId, LocalDate incidentDate) {
        return payslipRepository.existsLockedPayslipCovering(employeeUserId, incidentDate);
    }

    /** Whether the employee may still raise a dispute on this penalty right now. */
    public boolean isDisputable(AttendancePenalty penalty, boolean alreadyDisputed) {
        return AttendancePenaltyStatus.PENDING_REVIEW.equals(penalty.getStatus())
                && !alreadyDisputed
                && !isPayrollLocked(penalty.getEmployeeUserId(), penalty.getIncidentDate());
    }

    @Transactional
    public PenaltyDisputeResponse raise(String actorEmail, UUID penaltyId, RaisePenaltyDisputeRequest req) {
        Employee actor = resolveEmployee(actorEmail);
        AttendancePenalty penalty = penaltyRepository.findById(penaltyId)
                .orElseThrow(() -> new IllegalArgumentException("Penalty not found"));
        if (!penalty.getEmployeeUserId().equals(actor.getUserId())) {
            throw new AccessDeniedException("You can only dispute your own penalties");
        }
        if (!AttendancePenaltyStatus.PENDING_REVIEW.equals(penalty.getStatus())) {
            throw new IllegalStateException("Only an active penalty can be disputed");
        }
        if (disputeRepository.findByPenaltyId(penaltyId).isPresent()) {
            throw new IllegalStateException("This penalty has already been disputed");
        }
        if (isPayrollLocked(actor.getUserId(), penalty.getIncidentDate())) {
            throw new IllegalStateException("Payroll for this period is already locked - raise this with HR directly");
        }

        EmployeeResponse.ManagerRef manager = employeeService.findCurrentManagersBulk(List.of(actor.getUserId())).get(actor.getUserId());
        AttendancePenaltyDispute dispute = AttendancePenaltyDispute.builder()
                .penaltyId(penaltyId)
                .employeeUserId(actor.getUserId())
                .reason(req.getReason().trim())
                .evidence(req.getEvidence() != null && !req.getEvidence().isBlank() ? req.getEvidence().trim() : null)
                .status(manager != null ? AttendancePenaltyDispute.PENDING_MANAGER : AttendancePenaltyDispute.PENDING_HR)
                .managerUserId(manager != null ? UUID.fromString(manager.getUserId()) : null)
                .build();
        dispute = disputeRepository.save(dispute);
        auditService.log(actor.getUserId(), "ATTENDANCE_PENALTY_DISPUTE_RAISED", dispute.getId(), null, dispute.getStatus());
        if (manager != null) {
            notificationService.send(UUID.fromString(manager.getUserId()), "ATTENDANCE_PENALTY_DISPUTE_RAISED",
                    "Penalty dispute to review",
                    actor.getFullName() + " disputed the attendance penalty for " + penalty.getIncidentDate().format(DATE_FMT) + ".",
                    "/my-team");
        }
        return toResponse(dispute, penalty, actor);
    }

    /** Approver queue: the caller's direct reports' disputes awaiting the manager, plus (HR) those awaiting HR. */
    @Transactional(readOnly = true)
    public List<PenaltyDisputeResponse> queue(String actorEmail) {
        Employee actor = resolveEmployee(actorEmail);
        List<AttendancePenaltyDispute> rows = new ArrayList<>();
        if (effectivePermissionService.hasAuthority(actor.getUser(), TEAM_AUTHORITY)) {
            List<UUID> reports = managerHistoryRepository.findCurrentDirectReportIds(actor.getUserId());
            if (!reports.isEmpty()) {
                rows.addAll(disputeRepository.findByStatusAndEmployeeUserIdInOrderByCreatedAtAsc(
                        AttendancePenaltyDispute.PENDING_MANAGER, reports));
            }
        }
        if (effectivePermissionService.hasAuthority(actor.getUser(), ORG_WIDE_AUTHORITY)) {
            rows.addAll(disputeRepository.findByStatusOrderByCreatedAtAsc(AttendancePenaltyDispute.PENDING_HR));
        }
        return toResponses(rows).stream()
                .filter(r -> r.getStatus() != null)
                .toList();
    }

    /** Manager step: forward to HR with a recommendation. */
    @Transactional
    public PenaltyDisputeResponse forward(String actorEmail, UUID disputeId, ForwardPenaltyDisputeRequest req) {
        Employee actor = resolveEmployee(actorEmail);
        AttendancePenaltyDispute dispute = requireDispute(disputeId);
        if (!AttendancePenaltyDispute.PENDING_MANAGER.equals(dispute.getStatus())) {
            throw new IllegalStateException("This dispute is not awaiting manager review");
        }
        Set<UUID> reports = Set.copyOf(managerHistoryRepository.findCurrentDirectReportIds(actor.getUserId()));
        if (!reports.contains(dispute.getEmployeeUserId())) {
            throw new AccessDeniedException("Employee is not your direct report");
        }
        dispute.setStatus(AttendancePenaltyDispute.PENDING_HR);
        dispute.setManagerUserId(actor.getUserId());
        dispute.setManagerRecommendation(req.getRecommendation());
        dispute.setManagerComment(req.getComment().trim());
        dispute.setManagerActedAt(LocalDateTime.now());
        disputeRepository.save(dispute);
        auditService.log(actor.getUserId(), "ATTENDANCE_PENALTY_DISPUTE_FORWARDED", dispute.getId(),
                AttendancePenaltyDispute.PENDING_MANAGER, AttendancePenaltyDispute.PENDING_HR + ":" + req.getRecommendation());
        notificationService.send(dispute.getEmployeeUserId(), "ATTENDANCE_PENALTY_DISPUTE_FORWARDED",
                "Penalty dispute sent to HR", "Your manager reviewed your penalty dispute and sent it to HR for the final decision.", "/attendance?tab=penalties");
        return toResponse(dispute);
    }

    /** HR step: final decision. REVERSED reverses the penalty via the existing reverse logic. */
    @Transactional
    public PenaltyDisputeResponse decide(String actorEmail, UUID disputeId, DecidePenaltyDisputeRequest req) {
        Employee actor = resolveEmployee(actorEmail);
        if (!effectivePermissionService.hasAuthority(actor.getUser(), ORG_WIDE_AUTHORITY)) {
            throw new AccessDeniedException("Only HR can make the final decision on a penalty dispute");
        }
        AttendancePenaltyDispute dispute = requireDispute(disputeId);
        if (!AttendancePenaltyDispute.PENDING_HR.equals(dispute.getStatus())) {
            throw new IllegalStateException("This dispute is not awaiting an HR decision");
        }
        if (dispute.getEmployeeUserId().equals(actor.getUserId())) {
            throw new AccessDeniedException("You cannot decide your own dispute");
        }
        AttendancePenalty penalty = penaltyRepository.findById(dispute.getPenaltyId())
                .orElseThrow(() -> new IllegalStateException("Penalty no longer exists"));
        boolean reverse = AttendancePenaltyDispute.REVERSED.equals(req.getDecision());
        if (reverse) {
            if (!AttendancePenaltyStatus.PENDING_REVIEW.equals(penalty.getStatus())) {
                throw new IllegalStateException("The penalty is no longer active, so it cannot be reversed");
            }
            if (isPayrollLocked(penalty.getEmployeeUserId(), penalty.getIncidentDate())) {
                throw new IllegalStateException("Payroll for this period is locked - adjust it through payroll instead");
            }
            attendancePenaltyService.reverseIfActive(penalty.getId(), actor.getUserId(),
                    "Dispute upheld in employee's favour: " + req.getComment().trim(), "ATTENDANCE_PENALTY_DISPUTE_REVERSED");
        }
        String before = dispute.getStatus();
        dispute.setStatus(reverse ? AttendancePenaltyDispute.REVERSED : AttendancePenaltyDispute.UPHELD);
        dispute.setResolvedBy(actor.getUserId());
        dispute.setResolutionComment(req.getComment().trim());
        dispute.setResolvedAt(LocalDateTime.now());
        disputeRepository.save(dispute);
        auditService.log(actor.getUserId(), "ATTENDANCE_PENALTY_DISPUTE_RESOLVED", dispute.getId(), before, dispute.getStatus());
        // The reversal itself already notified the employee; an upheld dispute still needs telling.
        if (!reverse) {
            notificationService.send(dispute.getEmployeeUserId(), "ATTENDANCE_PENALTY_DISPUTE_UPHELD",
                    "Penalty dispute decided",
                    "Your dispute of the penalty for " + penalty.getIncidentDate().format(DATE_FMT)
                            + " was reviewed and the penalty stands. Reason: " + req.getComment().trim(),
                    "/attendance?tab=penalties");
        }
        return toResponse(dispute);
    }

    /** Disputes for a set of penalties, keyed by penalty id - used by My Penalties and the payroll review tab. */
    @Transactional(readOnly = true)
    public Map<UUID, AttendancePenaltyDispute> byPenaltyIds(Collection<UUID> penaltyIds) {
        if (penaltyIds.isEmpty()) return Map.of();
        return disputeRepository.findByPenaltyIdIn(penaltyIds).stream()
                .collect(Collectors.toMap(AttendancePenaltyDispute::getPenaltyId, Function.identity()));
    }

    // -- mapping ----------------------------------------------------------------------------

    private PenaltyDisputeResponse toResponse(AttendancePenaltyDispute d) {
        return toResponses(List.of(d)).get(0);
    }

    private PenaltyDisputeResponse toResponse(AttendancePenaltyDispute d, AttendancePenalty p, Employee e) {
        return map(d, p, e);
    }

    private List<PenaltyDisputeResponse> toResponses(List<AttendancePenaltyDispute> disputes) {
        if (disputes.isEmpty()) return List.of();
        Map<UUID, AttendancePenalty> penalties = penaltyRepository
                .findAllById(disputes.stream().map(AttendancePenaltyDispute::getPenaltyId).toList()).stream()
                .collect(Collectors.toMap(AttendancePenalty::getId, Function.identity()));
        Map<UUID, Employee> employees = new HashMap<>();
        employeeRepository.findAllByIdWithScheduleDetails(
                disputes.stream().map(AttendancePenaltyDispute::getEmployeeUserId).distinct().toList())
                .forEach(e -> employees.put(e.getUserId(), e));
        List<PenaltyDisputeResponse> out = new ArrayList<>();
        for (AttendancePenaltyDispute d : disputes) {
            AttendancePenalty p = penalties.get(d.getPenaltyId());
            // A pending dispute whose penalty was cancelled/reversed some other way is moot - hide it from queues.
            boolean moot = p == null || (isPending(d) && !AttendancePenaltyStatus.PENDING_REVIEW.equals(p.getStatus()));
            if (moot) continue;
            out.add(map(d, p, employees.get(d.getEmployeeUserId())));
        }
        return out;
    }

    private static boolean isPending(AttendancePenaltyDispute d) {
        return AttendancePenaltyDispute.PENDING_MANAGER.equals(d.getStatus()) || AttendancePenaltyDispute.PENDING_HR.equals(d.getStatus());
    }

    private PenaltyDisputeResponse map(AttendancePenaltyDispute d, AttendancePenalty p, Employee e) {
        return PenaltyDisputeResponse.builder()
                .id(d.getId()).penaltyId(d.getPenaltyId()).employeeUserId(d.getEmployeeUserId())
                .employeeName(e != null ? e.getFullName() : null).employeeCode(e != null ? e.getEmployeeCode() : null)
                .incidentDate(p != null ? p.getIncidentDate() : null)
                .rule(p != null ? MyPenaltyService.ruleLabel(p.getDiscrepancyType()) : null)
                .deductionDays(p != null ? p.getDeductionDays() : null)
                .reason(d.getReason()).evidence(d.getEvidence()).status(d.getStatus())
                .managerRecommendation(d.getManagerRecommendation()).managerComment(d.getManagerComment())
                .managerActedAt(d.getManagerActedAt())
                .resolutionComment(d.getResolutionComment()).resolvedAt(d.getResolvedAt())
                .createdAt(d.getCreatedAt())
                .build();
    }

    private AttendancePenaltyDispute requireDispute(UUID id) {
        return disputeRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Dispute not found"));
    }

    private Employee resolveEmployee(String email) {
        return employeeRepository.findByUser_Email(email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }
}
