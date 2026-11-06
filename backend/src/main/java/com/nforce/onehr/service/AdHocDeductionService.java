package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.AdHocDeductionInstallmentResponse;
import com.nforce.onehr.dto.payroll.AdHocDeductionResponse;
import com.nforce.onehr.dto.payroll.CreateAdHocDeductionRequest;
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
 * US-C25 (ONEHR-579) — create/cancel/list ad-hoc deduction recovery plans. See {@link
 * AdHocDeduction}'s own Javadoc for the installment model; {@code PayrollRunService#buildPayslip}
 * is what actually applies an installment to a payslip, and {@code #rollbackWholeRun} is what
 * reverses one.
 */
@Service
@RequiredArgsConstructor
public class AdHocDeductionService {

    private final AdHocDeductionRepository adHocDeductionRepository;
    private final AdHocDeductionInstallmentRepository adHocDeductionInstallmentRepository;
    private final SalaryComponentRepository salaryComponentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public AdHocDeductionResponse create(CreateAdHocDeductionRequest req, String actorEmail) {
        if (!employeeRepository.existsById(req.getEmployeeId())) {
            throw new NoSuchElementException("Employee not found: " + req.getEmployeeId());
        }
        SalaryComponent catalog = salaryComponentRepository.findByNameIgnoreCase(req.getComponentName())
                .orElseThrow(() -> new NoSuchElementException("Unknown salary component: " + req.getComponentName()));
        if (!catalog.isActive()) {
            throw new IllegalArgumentException("Salary component '" + catalog.getName() + "' is not active");
        }
        if (!SalaryComponentType.AD_HOC_DEDUCTION.equals(catalog.getType())) {
            throw new IllegalArgumentException(
                    "'" + catalog.getName() + "' is a " + catalog.getType() + " component — ad-hoc deductions only accept AD_HOC_DEDUCTION");
        }

        User actor = requireActor(actorEmail);
        AdHocDeduction deduction = adHocDeductionRepository.save(AdHocDeduction.builder()
                .employeeId(req.getEmployeeId())
                .componentId(catalog.getId())
                .componentName(catalog.getName())
                .componentType(catalog.getType())
                .totalAmount(req.getTotalAmount())
                .installmentAmount(req.getInstallmentAmount())
                .remainingBalance(req.getTotalAmount())
                .reason(req.getReason().trim())
                .createdBy(actor.getId())
                .build());

        auditService.log(actor.getId(), "PAYROLL_ADHOC_DEDUCTION_CREATED", deduction.getId(), null, auditSnapshot.toJson(deductionSnapshot(deduction)));
        return toResponse(deduction);
    }

    @Transactional
    public AdHocDeductionResponse cancel(UUID deductionId, String actorEmail) {
        AdHocDeduction deduction = requireDeduction(deductionId);
        if (!AdHocDeductionStatus.ACTIVE.equals(deduction.getStatus())) {
            throw new IllegalStateException("Only an ACTIVE deduction can be cancelled (currently " + deduction.getStatus() + ")");
        }

        User actor = requireActor(actorEmail);
        String before = auditSnapshot.toJson(deductionSnapshot(deduction));
        // Stops future installments only — any already applied to a run (even one still DRAFT)
        // stands until that run is regenerated or rolled back; this never retroactively touches
        // another run's payslip.
        deduction.setStatus(AdHocDeductionStatus.CANCELLED);
        deduction = adHocDeductionRepository.save(deduction);
        auditService.log(actor.getId(), "PAYROLL_ADHOC_DEDUCTION_CANCELLED", deduction.getId(), before, auditSnapshot.toJson(deductionSnapshot(deduction)));
        return toResponse(deduction);
    }

    @Transactional(readOnly = true)
    public AdHocDeductionResponse get(UUID deductionId) {
        return toResponse(requireDeduction(deductionId));
    }

    @Transactional(readOnly = true)
    public List<AdHocDeductionResponse> listByEmployee(UUID employeeId) {
        return adHocDeductionRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AdHocDeductionInstallmentResponse> listInstallments(UUID deductionId) {
        requireDeduction(deductionId);
        return adHocDeductionInstallmentRepository.findByAdHocDeductionId(deductionId).stream()
                .map(i -> AdHocDeductionInstallmentResponse.builder()
                        .id(i.getId())
                        .payrollRunId(i.getPayrollRun().getId())
                        .amount(i.getAmount())
                        .appliedAt(i.getAppliedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private AdHocDeduction requireDeduction(UUID id) {
        return adHocDeductionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Ad-hoc deduction not found: " + id));
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> deductionSnapshot(AdHocDeduction d) {
        return Map.of(
                "employeeId", d.getEmployeeId(),
                "componentName", d.getComponentName(),
                "totalAmount", d.getTotalAmount(),
                "installmentAmount", d.getInstallmentAmount(),
                "remainingBalance", d.getRemainingBalance(),
                "status", d.getStatus());
    }

    private AdHocDeductionResponse toResponse(AdHocDeduction d) {
        return AdHocDeductionResponse.builder()
                .id(d.getId())
                .employeeId(d.getEmployeeId())
                .componentId(d.getComponentId())
                .componentName(d.getComponentName())
                .componentType(d.getComponentType())
                .totalAmount(d.getTotalAmount())
                .installmentAmount(d.getInstallmentAmount())
                .remainingBalance(d.getRemainingBalance())
                .status(d.getStatus())
                .reason(d.getReason())
                .createdByName(employeeRepository.findById(d.getCreatedBy()).map(Employee::getFullName)
                        .orElseGet(() -> userRepository.findById(d.getCreatedBy()).map(User::getEmail).orElse("Unknown")))
                .createdAt(d.getCreatedAt())
                .build();
    }
}
