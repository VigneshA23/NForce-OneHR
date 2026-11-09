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
 * US-C26 (ONEHR-579) — both "Hold processing" and "Hold payout", see {@link SalaryHold}'s own
 * Javadoc for the full split. A {@link SalaryHoldType#PROCESSING} hold only ever affects a future
 * {@code PayrollRunService#generateDraft} regenerate, so it's gated to the run's DRAFT status —
 * same guard {@code AdHocPaymentService}/{@code AdHocDeductionService} already use. A {@link
 * SalaryHoldType#PAYOUT} hold is designed to outlive DRAFT: it can be created any time before the
 * run is DISBURSED (too late — the one moment it's meant to intercept has already happened), and
 * released any time after — releasing one against an already-DISBURSED run pays that employee now
 * by flipping their already-built {@link com.nforce.onehr.entity.Payslip#isPaymentWithheld()} back
 * off, rather than waiting on some later run that may never come.
 */
@Service
@RequiredArgsConstructor
public class SalaryHoldService {

    private final SalaryHoldRepository salaryHoldRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayslipRepository payslipRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public SalaryHoldResponse create(UUID runId, CreateSalaryHoldRequest req, String actorEmail) {
        PayrollRun run = requireRun(runId);
        if (SalaryHoldType.PROCESSING.equals(req.getType())) {
            if (!PayrollRunStatus.DRAFT.equals(run.getStatus())) {
                throw new IllegalStateException("A PROCESSING hold can only be created while the run is DRAFT (currently " + run.getStatus() + ")");
            }
        } else if (PayrollRunStatus.DISBURSED.equals(run.getStatus())) {
            throw new IllegalStateException("A PAYOUT hold must be created before the run is disbursed (currently DISBURSED)");
        }

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
                .holdType(req.getType())
                .createdBy(actor.getId())
                .build());

        auditService.log(actor.getId(), "PAYROLL_SALARY_HOLD_CREATED", hold.getId(), null, auditSnapshot.toJson(holdSnapshot(hold)));
        return toResponse(hold);
    }

    @Transactional
    public SalaryHoldResponse release(UUID runId, UUID holdId, String actorEmail) {
        PayrollRun run = requireRun(runId);
        SalaryHold hold = salaryHoldRepository.findById(holdId)
                .orElseThrow(() -> new NoSuchElementException("Salary hold not found: " + holdId));
        if (!hold.getPayrollRun().getId().equals(runId)) {
            throw new IllegalArgumentException("Salary hold " + holdId + " does not belong to run " + runId);
        }
        if (!SalaryHoldStatus.ACTIVE.equals(hold.getStatus())) {
            throw new IllegalStateException("Only an ACTIVE hold can be released (currently " + hold.getStatus() + ")");
        }
        // PROCESSING only ever affects a future regenerate, so (like creating one) it only makes
        // sense while the run is still DRAFT — unlike PAYOUT, which is designed to outlive DRAFT.
        if (SalaryHoldType.PROCESSING.equals(hold.getHoldType()) && !PayrollRunStatus.DRAFT.equals(run.getStatus())) {
            throw new IllegalStateException("A PROCESSING hold can only be released while the run is DRAFT (currently " + run.getStatus() + ")");
        }

        User actor = requireActor(actorEmail);
        String before = auditSnapshot.toJson(holdSnapshot(hold));
        hold.setStatus(SalaryHoldStatus.RELEASED);
        hold.setReleasedBy(actor.getId());
        hold.setReleasedAt(java.time.LocalDateTime.now());
        hold = salaryHoldRepository.save(hold);

        // US-C26 "Hold payout": if the run was already disbursed with this employee's payment
        // withheld, releasing the hold pays them now — flip the already-built payslip's flag
        // rather than waiting on some later run that may never come.
        if (SalaryHoldType.PAYOUT.equals(hold.getHoldType()) && PayrollRunStatus.DISBURSED.equals(run.getStatus())) {
            payslipRepository.findByPayrollRunIdAndEmployeeId(runId, hold.getEmployeeId())
                    .ifPresent(p -> { p.setPaymentWithheld(false); payslipRepository.save(p); });
        }

        auditService.log(actor.getId(), "PAYROLL_SALARY_HOLD_RELEASED", hold.getId(), before, auditSnapshot.toJson(holdSnapshot(hold)));
        return toResponse(hold);
    }

    @Transactional(readOnly = true)
    public List<SalaryHoldResponse> list(UUID runId) {
        requireRun(runId);
        return salaryHoldRepository.findByPayrollRunId(runId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private PayrollRun requireRun(UUID runId) {
        return payrollRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Payroll run not found: " + runId));
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
                "holdType", h.getHoldType(),
                "status", h.getStatus());
    }

    private SalaryHoldResponse toResponse(SalaryHold h) {
        return SalaryHoldResponse.builder()
                .id(h.getId())
                .payrollRunId(h.getPayrollRun().getId())
                .employeeId(h.getEmployeeId())
                .reason(h.getReason())
                .holdType(h.getHoldType())
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
