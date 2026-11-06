package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.AddAdHocPaymentRequest;
import com.nforce.onehr.dto.payroll.AdHocPaymentResponse;
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
 * US-C24 (ONEHR-579) — add/remove/list one-off ad-hoc payments on a DRAFT payroll run. See {@link
 * AdHocPayment}'s own Javadoc for why there's no separate "processed" flag: the run's own status
 * is the mutation gate, same as every other DRAFT-only guard in this epic.
 */
@Service
@RequiredArgsConstructor
public class AdHocPaymentService {

    private final AdHocPaymentRepository adHocPaymentRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayslipRepository payslipRepository;
    private final SalaryComponentRepository salaryComponentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public AdHocPaymentResponse add(UUID runId, AddAdHocPaymentRequest req, String actorEmail) {
        PayrollRun run = requireDraftRun(runId);

        if (!employeeRepository.existsById(req.getEmployeeId())) {
            throw new NoSuchElementException("Employee not found: " + req.getEmployeeId());
        }
        // The employee must actually be part of this run (i.e. have a payslip in it) — an ad-hoc
        // payment for someone outside the run has nothing to attach to once buildPayslip runs.
        if (payslipRepository.findByPayrollRunIdAndEmployeeId(runId, req.getEmployeeId()).isEmpty()) {
            throw new IllegalArgumentException("Employee " + req.getEmployeeId() + " has no payslip in this run");
        }

        SalaryComponent catalog = salaryComponentRepository.findByNameIgnoreCase(req.getComponentName())
                .orElseThrow(() -> new NoSuchElementException("Unknown salary component: " + req.getComponentName()));
        if (!catalog.isActive()) {
            throw new IllegalArgumentException("Salary component '" + catalog.getName() + "' is not active");
        }
        if (!SalaryComponentType.AD_HOC_EARNING.equals(catalog.getType())) {
            throw new IllegalArgumentException(
                    "'" + catalog.getName() + "' is a " + catalog.getType() + " component — ad-hoc payments only accept AD_HOC_EARNING");
        }

        User actor = requireActor(actorEmail);
        AdHocPayment payment = adHocPaymentRepository.save(AdHocPayment.builder()
                .payrollRun(run)
                .employeeId(req.getEmployeeId())
                .componentId(catalog.getId())
                .componentName(catalog.getName())
                .componentType(catalog.getType())
                .amount(req.getAmount())
                .reason(req.getReason().trim())
                .addedBy(actor.getId())
                .build());

        auditService.log(actor.getId(), "PAYROLL_ADHOC_PAYMENT_ADDED", payment.getId(), null, auditSnapshot.toJson(paymentSnapshot(payment)));
        return toResponse(payment);
    }

    @Transactional
    public void remove(UUID runId, UUID paymentId, String actorEmail) {
        requireDraftRun(runId);
        AdHocPayment payment = adHocPaymentRepository.findById(paymentId)
                .orElseThrow(() -> new NoSuchElementException("Ad-hoc payment not found: " + paymentId));
        if (!payment.getPayrollRun().getId().equals(runId)) {
            throw new IllegalArgumentException("Ad-hoc payment " + paymentId + " does not belong to run " + runId);
        }

        User actor = requireActor(actorEmail);
        String before = auditSnapshot.toJson(paymentSnapshot(payment));
        adHocPaymentRepository.delete(payment);
        auditService.log(actor.getId(), "PAYROLL_ADHOC_PAYMENT_REMOVED", paymentId, before, null);
    }

    @Transactional(readOnly = true)
    public List<AdHocPaymentResponse> list(UUID runId) {
        requireRun(runId);
        return adHocPaymentRepository.findByPayrollRunId(runId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private PayrollRun requireDraftRun(UUID runId) {
        PayrollRun run = requireRun(runId);
        if (!PayrollRunStatus.DRAFT.equals(run.getStatus())) {
            throw new IllegalStateException("Ad-hoc payments can only be added or removed while the run is DRAFT (currently " + run.getStatus() + ")");
        }
        return run;
    }

    private PayrollRun requireRun(UUID runId) {
        return payrollRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Payroll run not found: " + runId));
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> paymentSnapshot(AdHocPayment p) {
        return Map.of(
                "payrollRunId", p.getPayrollRun().getId(),
                "employeeId", p.getEmployeeId(),
                "componentName", p.getComponentName(),
                "amount", p.getAmount(),
                "reason", p.getReason());
    }

    private AdHocPaymentResponse toResponse(AdHocPayment p) {
        return AdHocPaymentResponse.builder()
                .id(p.getId())
                .payrollRunId(p.getPayrollRun().getId())
                .employeeId(p.getEmployeeId())
                .componentId(p.getComponentId())
                .componentName(p.getComponentName())
                .componentType(p.getComponentType())
                .amount(p.getAmount())
                .reason(p.getReason())
                .addedByName(employeeRepository.findById(p.getAddedBy()).map(Employee::getFullName)
                        .orElseGet(() -> userRepository.findById(p.getAddedBy()).map(User::getEmail).orElse("Unknown")))
                .addedAt(p.getAddedAt())
                .build();
    }
}
