package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.AssignCompensationRequest;
import com.nforce.onehr.dto.payroll.CurrentSalaryStructureRowResponse;
import com.nforce.onehr.dto.payroll.EmployeeCompensationResponse;
import com.nforce.onehr.dto.payroll.MyCtcComponentResponse;
import com.nforce.onehr.entity.ArrearsSourceType;
import com.nforce.onehr.entity.EmployeeCompensation;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.entity.PayrollRunStatus;
import com.nforce.onehr.entity.SalaryStructure;
import com.nforce.onehr.entity.SalaryStructureComponent;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeCompensationRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.SalaryStructureComponentRepository;
import com.nforce.onehr.repository.SalaryStructureRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C02 (ONEHR-579, Phase 1) — assigns a {@link SalaryStructure} and CTC to an employee, and
 * revises it later. Effective-dated the same way as {@link EmployeeManagerHistory}: a revision is
 * never an UPDATE in place, it's INSERT new row + close the prior row's effectiveTo in the same
 * transaction. This service does not resolve the structure's components into actual payslip
 * amounts — that's a Phase 3 (payroll run) concern, not this story's.
 *
 * <p>C03's backdating guard ("reject effectiveFrom falling inside a PayrollRun already
 * APPROVED/DISBURSED") isn't enforced here: {@code PayrollRun} doesn't exist yet (Phase 3). The
 * schema is shaped so that check can be added later without a migration.
 */
@Service
@RequiredArgsConstructor
public class EmployeeCompensationService {

    private final EmployeeCompensationRepository employeeCompensationRepository;
    private final EmployeeRepository employeeRepository;
    private final PayGroupRepository payGroupRepository;
    private final SalaryStructureRepository salaryStructureRepository;
    private final SalaryStructureComponentRepository salaryStructureComponentRepository;
    private final PayslipRepository payslipRepository;
    private final PayrollRunService payrollRunService;
    private final ArrearsService arrearsService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final SalaryStructureResolutionService salaryStructureResolutionService;

    private static final int MONTHS_PER_YEAR = 12;

    @Transactional
    public EmployeeCompensationResponse assign(AssignCompensationRequest req, String actorEmail) {
        if (!employeeRepository.existsById(req.getEmployeeId())) {
            throw new NoSuchElementException("Employee not found: " + req.getEmployeeId());
        }
        if (!payGroupRepository.existsById(req.getPayGroupId())) {
            throw new NoSuchElementException("Pay group not found: " + req.getPayGroupId());
        }
        SalaryStructure structure = salaryStructureRepository.findById(req.getStructureId())
                .orElseThrow(() -> new NoSuchElementException("Salary structure not found: " + req.getStructureId()));
        if (!salaryStructureComponentRepository.existsByStructureId(structure.getId())) {
            throw new IllegalArgumentException("Salary structure '" + structure.getName() + "' has no components and can't be assigned");
        }

        Optional<EmployeeCompensation> current = employeeCompensationRepository
                .findByEmployeeIdAndEffectiveToIsNull(req.getEmployeeId());
        current.ifPresent(c -> {
            if (!req.getEffectiveFrom().isAfter(c.getEffectiveFrom())) {
                throw new IllegalArgumentException(
                        "effectiveFrom must be after the current compensation's effectiveFrom (" + c.getEffectiveFrom() + ")");
            }
        });

        String before = current.map(c -> auditSnapshot.toJson(compensationSnapshot(c))).orElse(null);

        if (current.isPresent()) {
            employeeCompensationRepository.closeCurrentEntry(req.getEmployeeId(), req.getEffectiveFrom());
        }

        EmployeeCompensation saved = employeeCompensationRepository.save(EmployeeCompensation.builder()
                .employeeId(req.getEmployeeId())
                .payGroupId(req.getPayGroupId())
                .structure(structure)
                .ctc(req.getCtc())
                .effectiveFrom(req.getEffectiveFrom())
                .build());

        User actor = requireActor(actorEmail);
        String after = auditSnapshot.toJson(compensationSnapshot(saved));
        auditService.log(actor.getId(), "EMPLOYEE_COMPENSATION_ASSIGNED", saved.getId(), before, after);

        raiseArrearsForBackdatedRevision(saved, actorEmail);

        return toResponse(saved);
    }

    // Arrears trigger 2 — see Arrears' own doc comment. A revision effectiveFrom on/before an
    // already-closed (APPROVED/DISBURSED) cycle's own end date means that cycle was paid at the
    // OLD rate; this computes what it would have paid under the NEW one and records the delta as a
    // PENDING Arrears row per affected cycle, without touching that cycle's real Payslip.
    private void raiseArrearsForBackdatedRevision(EmployeeCompensation revised, String actorEmail) {
        List<Payslip> closedCyclePayslips = payslipRepository.findVisibleForEmployee(
                revised.getEmployeeId(), Set.of(PayrollRunStatus.APPROVED, PayrollRunStatus.DISBURSED));

        for (Payslip historical : closedCyclePayslips) {
            if (historical.getPayrollRun().getCycleEnd().isBefore(revised.getEffectiveFrom())) {
                continue; // this cycle ended before the revision takes effect — unaffected
            }
            BigDecimal revisedNetPay = payrollRunService.previewNetPay(revised,
                    historical.getPayrollRun().getCycleStart(), historical.getPayrollRun().getCycleEnd(), historical.getProrationFactor());
            BigDecimal delta = revisedNetPay.subtract(historical.getNetPay());
            arrearsService.createPending(revised.getEmployeeId(), revised.getPayGroupId(), delta,
                    "Backdated compensation revision effective " + revised.getEffectiveFrom() + " affects the cycle "
                            + historical.getPayrollRun().getCycleStart() + "-" + historical.getPayrollRun().getCycleEnd(),
                    ArrearsSourceType.SALARY_REVISION, historical.getPayrollRun().getCycleStart(), historical.getPayrollRun().getCycleEnd(), actorEmail);
        }
    }

    @Transactional(readOnly = true)
    public EmployeeCompensationResponse getCurrent(UUID employeeId) {
        EmployeeCompensation current = employeeCompensationRepository.findByEmployeeIdAndEffectiveToIsNull(employeeId)
                .orElseThrow(() -> new NoSuchElementException("No current compensation for employee: " + employeeId));
        return toResponse(current);
    }

    @Transactional(readOnly = true)
    public List<EmployeeCompensationResponse> getHistory(UUID employeeId) {
        return employeeCompensationRepository.findByEmployeeIdOrderByEffectiveFromDesc(employeeId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Keka restyle — "Current CTC Report" (§3.6 Reports Home): every employee's compensation row
    // effective on the given date, reusing the exact point-in-time query generateDraft already
    // relies on (US-C08) rather than a second "current" concept of its own.
    @Transactional(readOnly = true)
    public List<EmployeeCompensationResponse> listAllCurrent(LocalDate asOf) {
        return employeeCompensationRepository.findAllEffectiveAt(asOf).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Org-wide "who was revised when" admin view — every compensation row across every employee,
    // newest first. The frontend joins each row against GET /api/audit-logs?action=
    // EMPLOYEE_COMPENSATION_ASSIGNED (by this row's own id, which is exactly the targetId that
    // call was logged against) to show who made the change and when, rather than this endpoint
    // carrying actor/timestamp fields of its own — the audit trail is already the one place that's
    // tracked, and duplicating it here would drift the moment either one changed independently.
    @Transactional(readOnly = true)
    public List<EmployeeCompensationResponse> listAllHistory() {
        return employeeCompensationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Keka restyle — "Employee CTC Report" (§3.6), filtered to a chosen date range across every
    // employee, newest first.
    @Transactional(readOnly = true)
    public List<EmployeeCompensationResponse> listAllBetween(LocalDate start, LocalDate end) {
        return employeeCompensationRepository.findAllEffectiveBetween(start, end).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Keka restyle — "Current Salary Structure" report (§3.6): the org-wide version of {@link
    // MyFinancesService#getCurrentCtcBreakdown} — same resolveAnnualValues engine, one row per
    // employee effective on the given date instead of "me" resolved from the JWT.
    @Transactional(readOnly = true)
    public List<CurrentSalaryStructureRowResponse> listAllCurrentBreakdown(LocalDate asOf) {
        return employeeCompensationRepository.findAllEffectiveAt(asOf).stream()
                .map(this::toBreakdownResponse)
                .collect(Collectors.toList());
    }

    private CurrentSalaryStructureRowResponse toBreakdownResponse(EmployeeCompensation c) {
        List<SalaryStructureComponent> components = salaryStructureComponentRepository.findByStructureId(c.getStructure().getId());
        Map<UUID, BigDecimal> annualByComponentId = salaryStructureResolutionService.resolveAnnualValues(components, c.getCtc());

        List<MyCtcComponentResponse> componentResponses = components.stream()
                .map(sc -> {
                    BigDecimal annual = annualByComponentId.get(sc.getId()).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal monthly = annual.divide(BigDecimal.valueOf(MONTHS_PER_YEAR), 2, RoundingMode.HALF_UP);
                    return MyCtcComponentResponse.builder()
                            .componentName(sc.getComponent().getName())
                            .componentType(sc.getComponent().getType())
                            .annualAmount(annual)
                            .monthlyAmount(monthly)
                            .build();
                })
                .collect(Collectors.toList());

        return CurrentSalaryStructureRowResponse.builder()
                .employeeId(c.getEmployeeId())
                .structureId(c.getStructure().getId())
                .structureName(c.getStructure().getName())
                .ctc(c.getCtc())
                .effectiveFrom(c.getEffectiveFrom())
                .components(componentResponses)
                .build();
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> compensationSnapshot(EmployeeCompensation c) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("payGroupId", c.getPayGroupId());
        snapshot.put("structureId", c.getStructure().getId());
        snapshot.put("ctc", c.getCtc());
        snapshot.put("effectiveFrom", c.getEffectiveFrom());
        snapshot.put("effectiveTo", c.getEffectiveTo());
        return snapshot;
    }

    private EmployeeCompensationResponse toResponse(EmployeeCompensation c) {
        return EmployeeCompensationResponse.builder()
                .id(c.getId())
                .employeeId(c.getEmployeeId())
                .payGroupId(c.getPayGroupId())
                .structureId(c.getStructure().getId())
                .structureName(c.getStructure().getName())
                .ctc(c.getCtc())
                .effectiveFrom(c.getEffectiveFrom())
                .effectiveTo(c.getEffectiveTo())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
