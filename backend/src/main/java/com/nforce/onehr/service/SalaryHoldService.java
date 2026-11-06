package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreateSalaryHoldRequest;
import com.nforce.onehr.dto.payroll.SalaryHoldResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C26 (ONEHR-579) — "Hold processing" only, see {@link SalaryHold}'s own Javadoc for the scope
 * split from Keka's "Hold payout". Mutation is gated by the run's own DRAFT status, same guard
 * {@code AdHocPaymentService}/{@code AdHocDeductionService} already use.
 */
@Service
@RequiredArgsConstructor
public class SalaryHoldService {

    private final SalaryHoldRepository salaryHoldRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public SalaryHoldResponse create(UUID runId, CreateSalaryHoldRequest req, String actorEmail) {
        PayrollRun run = requireDraftRun(runId);

        if (!employeeRepository.existsById(req.getEmployeeId())) {
            throw new NoSuchElementException("Employee not found: " + req.getEmployeeId());
        }
        if (salaryHoldRepository.existsByPayrollRunIdAndEmployeeIdAndStatus(runId, req.getEmployeeId(), SalaryHoldStatus.ACTIVE)) {
            throw new IllegalArgumentException("Employee " + req.getEmployeeId() + " already has an active hold on this run");
        }

        User actor = requireActor(actorEmail);
        SalaryHold hold = salaryHoldRepository.save(SalaryHold.builder()
                .payrollRun(run)
                .employeeId(req.getEmployeeId())
                .reason(req.getReason().trim())
                .createdBy(actor.getId())
                .build());

        auditService.log(actor.getId(), "PAYROLL_SALARY_HOLD_CREATED", hold.getId(), null, auditSnapshot.toJson(holdSnapshot(hold)));
        return toResponse(hold);
    }

    @Transactional
    public SalaryHoldResponse release(UUID runId, UUID holdId, String actorEmail) {
        requireDraftRun(runId);
        SalaryHold hold = salaryHoldRepository.findById(holdId)
                .orElseThrow(() -> new NoSuchElementException("Salary hold not found: " + holdId));
        if (!hold.getPayrollRun().getId().equals(runId)) {
            throw new IllegalArgumentException("Salary hold " + holdId + " does not belong to run " + runId);
        }
        if (!SalaryHoldStatus.ACTIVE.equals(hold.getStatus())) {
            throw new IllegalStateException("Only an ACTIVE hold can be released (currently " + hold.getStatus() + ")");
        }

        User actor = requireActor(actorEmail);
        String before = auditSnapshot.toJson(holdSnapshot(hold));
        hold.setStatus(SalaryHoldStatus.RELEASED);
        hold.setReleasedBy(actor.getId());
        hold.setReleasedAt(java.time.LocalDateTime.now());
        hold = salaryHoldRepository.save(hold);
        auditService.log(actor.getId(), "PAYROLL_SALARY_HOLD_RELEASED", hold.getId(), before, auditSnapshot.toJson(holdSnapshot(hold)));
        return toResponse(hold);
    }

    @Transactional(readOnly = true)
    public List<SalaryHoldResponse> list(UUID runId) {
        return salaryHoldRepository.findByPayrollRunId(runId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private PayrollRun requireDraftRun(UUID runId) {
        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Payroll run not found: " + runId));
        if (!PayrollRunStatus.DRAFT.equals(run.getStatus())) {
            throw new IllegalStateException("Salary holds can only be created or released while the run is DRAFT (currently " + run.getStatus() + ")");
        }
        return run;
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> holdSnapshot(SalaryHold h) {
        return Map.of(
                "payrollRunId", h.getPayrollRun().getId(),
                "employeeId", h.getEmployeeId(),
                "reason", h.getReason(),
                "status", h.getStatus());
    }

    private SalaryHoldResponse toResponse(SalaryHold h) {
        return SalaryHoldResponse.builder()
                .id(h.getId())
                .payrollRunId(h.getPayrollRun().getId())
                .employeeId(h.getEmployeeId())
                .reason(h.getReason())
                .status(h.getStatus())
                .createdByName(employeeRepository.findById(h.getCreatedBy()).map(Employee::getFullName)
                        .orElseGet(() -> userRepository.findById(h.getCreatedBy()).map(User::getEmail).orElse("Unknown")))
                .createdAt(h.getCreatedAt())
                .releasedByName(h.getReleasedBy() != null
                        ? employeeRepository.findById(h.getReleasedBy()).map(Employee::getFullName)
                                .orElseGet(() -> userRepository.findById(h.getReleasedBy()).map(User::getEmail).orElse("Unknown"))
                        : null)
                .releasedAt(h.getReleasedAt())
                .build();
    }
}
