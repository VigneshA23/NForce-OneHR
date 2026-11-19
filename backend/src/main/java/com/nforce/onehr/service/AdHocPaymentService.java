package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.AddAdHocPaymentRequest;
import com.nforce.onehr.dto.payroll.AdHocPaymentResponse;
import com.nforce.onehr.dto.payroll.BulkAdHocPaymentResult;
import com.nforce.onehr.dto.payroll.BulkAddAdHocPaymentRequest;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
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

        SalaryComponent catalog = requireActiveAdHocEarning(req.getComponentName());

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

    /** Same component/amount/reason applied to every employee with a payslip in this run — the
     *  recurring "everyone gets Food Allowance this month" case, without an HR Admin picking each
     *  employee one at a time through {@link #add}. Employees who already have this exact
     *  component name added in this run are skipped (not re-added), so clicking this twice by
     *  mistake can't double everyone up. One {@code saveAll} for the whole batch and one summary
     *  audit row — never a per-employee save/log loop (see PayrollRunReviewService#
     *  bulkDecideNoAttendance's own Javadoc for why that shape reliably times out against Neon at
     *  real employee-count batch sizes). */
    @Transactional
    public BulkAdHocPaymentResult bulkAdd(UUID runId, BulkAddAdHocPaymentRequest req, String actorEmail) {
        PayrollRun run = requireDraftRun(runId);
        SalaryComponent catalog = requireActiveAdHocEarning(req.getComponentName());
        User actor = requireActor(actorEmail);

        List<UUID> employeeIds = payslipRepository.findByPayrollRunId(runId).stream()
                .map(Payslip::getEmployeeId).collect(Collectors.toList());
        Set<UUID> alreadyCovered = new HashSet<>(
                adHocPaymentRepository.findEmployeeIdsByPayrollRunIdAndComponentName(runId, catalog.getName()));

        LocalDateTime addedAt = LocalDateTime.now();
        List<AdHocPayment> toCreate = new ArrayList<>();
        for (UUID employeeId : employeeIds) {
            if (alreadyCovered.contains(employeeId)) continue;
            toCreate.add(AdHocPayment.builder()
                    .payrollRun(run)
                    .employeeId(employeeId)
                    .componentId(catalog.getId())
                    .componentName(catalog.getName())
                    .componentType(catalog.getType())
                    .amount(req.getAmount())
                    .reason(req.getReason().trim())
                    .addedBy(actor.getId())
                    .addedAt(addedAt)
                    .build());
        }
        List<AdHocPayment> saved = adHocPaymentRepository.saveAll(toCreate);

        auditService.log(actor.getId(), "PAYROLL_ADHOC_PAYMENT_BULK_ADDED", runId, null,
                auditSnapshot.toJson(Map.of("componentName", catalog.getName(), "amount", req.getAmount(),
                        "employeeCount", saved.size(), "skippedCount", employeeIds.size() - saved.size())));

        return BulkAdHocPaymentResult.builder()
                .added(toResponses(saved))
                .skippedCount(employeeIds.size() - saved.size())
                .build();
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
        return toResponses(adHocPaymentRepository.findByPayrollRunId(runId));
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

    private SalaryComponent requireActiveAdHocEarning(String componentName) {
        SalaryComponent catalog = salaryComponentRepository.findByNameIgnoreCase(componentName)
                .orElseThrow(() -> new NoSuchElementException("Unknown salary component: " + componentName));
        if (!catalog.isActive()) {
            throw new IllegalArgumentException("Salary component '" + catalog.getName() + "' is not active");
        }
        if (!SalaryComponentType.AD_HOC_EARNING.equals(catalog.getType())) {
            throw new IllegalArgumentException(
                    "'" + catalog.getName() + "' is a " + catalog.getType() + " component — ad-hoc payments only accept AD_HOC_EARNING");
        }
        return catalog;
    }

    private Map<String, Object> paymentSnapshot(AdHocPayment p) {
        return Map.of(
                "payrollRunId", p.getPayrollRun().getId(),
                "employeeId", p.getEmployeeId(),
                "componentName", p.getComponentName(),
                "amount", p.getAmount(),
                "reason", p.getReason());
    }

    /** Batch display-name resolution for a list of payments — one {@code findAllById} across every
     *  distinct addedBy in the whole list, instead of {@link #toResponse(AdHocPayment)} doing a
     *  {@code findById} per payment (harmless for the single-item {@link #add}, but a real N+1 for
     *  {@link #list} and {@link #bulkAdd} — same mistake PayrollRunReviewService#toResponses was
     *  just fixed for). */
    private List<AdHocPaymentResponse> toResponses(List<AdHocPayment> payments) {
        Set<UUID> addedByIds = payments.stream().map(AdHocPayment::getAddedBy).collect(Collectors.toSet());
        Map<UUID, String> namesById = new HashMap<>();
        if (!addedByIds.isEmpty()) {
            for (Employee e : employeeRepository.findAllById(addedByIds)) {
                namesById.put(e.getUserId(), e.getFullName());
            }
            Set<UUID> remaining = new HashSet<>(addedByIds);
            remaining.removeAll(namesById.keySet());
            if (!remaining.isEmpty()) {
                for (User u : userRepository.findAllById(remaining)) {
                    namesById.put(u.getId(), u.getEmail());
                }
            }
        }
        return payments.stream().map(p -> toResponse(p, namesById)).collect(Collectors.toList());
    }

    private AdHocPaymentResponse toResponse(AdHocPayment p) {
        return toResponse(p, null);
    }

    private AdHocPaymentResponse toResponse(AdHocPayment p, Map<UUID, String> namesById) {
        String addedByName = namesById != null
                ? namesById.getOrDefault(p.getAddedBy(), "Unknown")
                : employeeRepository.findById(p.getAddedBy()).map(Employee::getFullName)
                        .orElseGet(() -> userRepository.findById(p.getAddedBy()).map(User::getEmail).orElse("Unknown"));
        return AdHocPaymentResponse.builder()
                .id(p.getId())
                .payrollRunId(p.getPayrollRun().getId())
                .employeeId(p.getEmployeeId())
                .componentId(p.getComponentId())
                .componentName(p.getComponentName())
                .componentType(p.getComponentType())
                .amount(p.getAmount())
                .reason(p.getReason())
                .addedByName(addedByName)
                .addedAt(p.getAddedAt())
                .build();
    }
}
