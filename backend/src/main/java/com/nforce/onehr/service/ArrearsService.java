package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.ArrearsResponse;
import com.nforce.onehr.entity.Arrears;
import com.nforce.onehr.entity.ArrearsStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.ArrearsRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Keka's "Arrears" step — see {@link Arrears}'s own doc comment for the three triggers and the
 * PENDING -> APPLIED lifecycle. This service owns creation (one shared entry point every trigger
 * calls) and read access; applying a PENDING row into a real {@link
 * com.nforce.onehr.entity.PayslipLineItem} happens inside {@code PayrollRunService#buildPayslip}
 * itself, alongside where statutory deductions already get turned into line items the same way.
 */
@Service
@RequiredArgsConstructor
public class ArrearsService {

    private final ArrearsRepository arrearsRepository;
    private final EmployeeRepository employeeRepository;
    private final PayslipRepository payslipRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    /** No-op (returns null) for a zero delta — nothing to correct, so nothing worth a row. */
    @Transactional
    public Arrears createPending(UUID employeeId, UUID payGroupId, BigDecimal amount, String reason,
                                  String sourceType, LocalDate sourceCycleStart, LocalDate sourceCycleEnd, String actorEmail) {
        if (amount.signum() == 0) {
            return null;
        }
        User actor = requireActor(actorEmail);
        Arrears row = Arrears.builder()
                .employeeId(employeeId).payGroupId(payGroupId).amount(amount).reason(reason)
                .sourceType(sourceType).sourceCycleStart(sourceCycleStart).sourceCycleEnd(sourceCycleEnd)
                .createdBy(actor.getId())
                .build();
        row = arrearsRepository.save(row);
        auditService.log(actor.getId(), "ARREARS_CREATED", row.getId(), null, auditSnapshot.toJson(snapshot(row)));
        return row;
    }

    @Transactional(readOnly = true)
    public List<Arrears> findPendingFor(UUID employeeId) {
        return arrearsRepository.findByEmployeeIdAndStatus(employeeId, ArrearsStatus.PENDING);
    }

    /** Idempotent in effect: a row already APPLIED to this exact run is simply saved again with the
     * same values, never re-matched by {@link #findPendingFor} a second time (status no longer
     * PENDING) — so a same-run regenerate can't double-apply it, same guarantee ad-hoc deduction
     * installments already have via their own findByAdHocDeductionIdAndPayrollRunId lookup. */
    @Transactional
    public void markApplied(Arrears row, UUID runId) {
        row.setStatus(ArrearsStatus.APPLIED);
        row.setAppliedToRunId(runId);
        arrearsRepository.save(row);
    }

    @Transactional(readOnly = true)
    public List<ArrearsResponse> listForRun(UUID runId) {
        List<UUID> employeeIds = payslipRepository.findByPayrollRunId(runId).stream().map(Payslip::getEmployeeId).toList();
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> nameByEmployeeId = employeeRepository.findAllByIdWithDepartment(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, Employee::getFullName));

        return arrearsRepository.findByEmployeeIdIn(employeeIds).stream()
                .sorted(Comparator.comparing(Arrears::getCreatedAt).reversed())
                .map(r -> ArrearsResponse.builder()
                        .id(r.getId()).employeeId(r.getEmployeeId()).employeeName(nameByEmployeeId.getOrDefault(r.getEmployeeId(), "Unknown"))
                        .amount(r.getAmount()).reason(r.getReason()).sourceType(r.getSourceType())
                        .sourceCycleStart(r.getSourceCycleStart()).sourceCycleEnd(r.getSourceCycleEnd())
                        .status(r.getStatus()).appliedToRunId(r.getAppliedToRunId()).createdAt(r.getCreatedAt())
                        .build())
                .toList();
    }

    private Map<String, Object> snapshot(Arrears r) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("amount", r.getAmount());
        snapshot.put("reason", r.getReason());
        snapshot.put("sourceType", r.getSourceType());
        snapshot.put("sourceCycleStart", r.getSourceCycleStart());
        snapshot.put("sourceCycleEnd", r.getSourceCycleEnd());
        return snapshot;
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }
}
