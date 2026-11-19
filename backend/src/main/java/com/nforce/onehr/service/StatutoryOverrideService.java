package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreateStatutoryOverrideRequest;
import com.nforce.onehr.dto.payroll.StatutoryOverrideResponse;
import com.nforce.onehr.dto.payroll.StatutoryPreviewResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeCompensation;
import com.nforce.onehr.entity.PayCycleWindow;
import com.nforce.onehr.entity.PayGroup;
import com.nforce.onehr.entity.SalaryComponent;
import com.nforce.onehr.entity.SalaryComponentType;
import com.nforce.onehr.entity.SalaryStructureComponent;
import com.nforce.onehr.entity.StatutoryDeductionOverride;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeCompensationRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.SalaryStructureComponentRepository;
import com.nforce.onehr.repository.StatutoryDeductionOverrideRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Keka's "Statutory Override" step — see {@link StatutoryDeductionOverride}'s own doc comment for
 * why this is a standalone row instead of a field on {@link com.nforce.onehr.entity.PayslipLineItem}.
 * Unlike {@link PayrollLopCalculationService#override}, there's no prerequisite "recompute first"
 * row to require — an override row's entire reason to exist is the override itself, so clearing
 * one deletes it outright rather than nulling a field on a row that would otherwise have nothing
 * left to hold.
 */
@Service
@RequiredArgsConstructor
public class StatutoryOverrideService {

    private static final int MONTHS_PER_YEAR = 12;
    private static final Set<String> EARNING_TYPES = Set.of(
            SalaryComponentType.EARNING, SalaryComponentType.AD_HOC_EARNING, SalaryComponentType.REIMBURSEMENT);

    private final StatutoryDeductionOverrideRepository statutoryDeductionOverrideRepository;
    private final PayGroupRepository payGroupRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeCompensationRepository employeeCompensationRepository;
    private final SalaryStructureComponentRepository salaryStructureComponentRepository;
    private final SalaryStructureResolutionService salaryStructureResolutionService;
    private final StatutoryDeductionService statutoryDeductionService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public StatutoryOverrideResponse createOrOverride(CreateStatutoryOverrideRequest req, String actorEmail) {
        PayCycleWindow window = resolveCycle(req.getPayGroupId(), req.getReferenceDate());
        Employee employee = employeeRepository.findById(req.getEmployeeId())
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + req.getEmployeeId()));
        if (employee.getLocation() == null || employee.getLocation().getCountry() == null) {
            throw new IllegalArgumentException("Employee has no Location/country set — can't key a statutory override without one");
        }
        if (req.getOverrideAmount() == null) {
            throw new IllegalArgumentException("overrideAmount is required — use clear() to remove an override instead");
        }
        String country = employee.getLocation().getCountry();

        StatutoryDeductionOverride row = statutoryDeductionOverrideRepository
                .findByEmployeeIdAndRuleTypeAndCycleStartAndCycleEnd(req.getEmployeeId(), req.getRuleType(), window.start(), window.end())
                .orElseGet(() -> StatutoryDeductionOverride.builder()
                        .employeeId(req.getEmployeeId()).country(country).ruleType(req.getRuleType())
                        .cycleStart(window.start()).cycleEnd(window.end()).build());

        String before = row.getId() == null ? null : auditSnapshot.toJson(snapshot(row));
        User actor = requireActor(actorEmail);
        row.setOverrideAmount(req.getOverrideAmount());
        row.setOverrideReason(req.getOverrideReason());
        row.setOverriddenBy(actor.getId());
        row.setOverriddenAt(LocalDateTime.now());
        row = statutoryDeductionOverrideRepository.save(row);

        auditService.log(actor.getId(), "STATUTORY_DEDUCTION_OVERRIDDEN", row.getId(), before, auditSnapshot.toJson(snapshot(row)));
        return toResponse(row);
    }

    @Transactional
    public void clear(UUID employeeId, String ruleType, UUID payGroupId, LocalDate referenceDate, String actorEmail) {
        PayCycleWindow window = resolveCycle(payGroupId, referenceDate);
        statutoryDeductionOverrideRepository
                .findByEmployeeIdAndRuleTypeAndCycleStartAndCycleEnd(employeeId, ruleType, window.start(), window.end())
                .ifPresent(row -> {
                    User actor = requireActor(actorEmail);
                    auditService.log(actor.getId(), "STATUTORY_DEDUCTION_OVERRIDE_CLEARED", row.getId(), auditSnapshot.toJson(snapshot(row)), null);
                    statutoryDeductionOverrideRepository.delete(row);
                });
    }

    /** What the {@link StatutoryDeductionService} formula alone would produce for this employee
     *  right now, for the Override form's "here's what you'd be overriding" reference — see
     *  {@link StatutoryDeductionService#previewFormulaAmount} for why any existing override is
     *  deliberately ignored here. Mirrors {@code PayrollRunService#previewNetPay}'s own
     *  structure-resolution shape, at full (unprorated) pay — this is "what PF/ESI/PT would
     *  normally be," not a recompute of any specific historical cycle's actual LOP. */
    @Transactional(readOnly = true)
    public StatutoryPreviewResponse previewComputedAmount(UUID employeeId, String ruleType, UUID payGroupId, LocalDate referenceDate) {
        PayCycleWindow window = resolveCycle(payGroupId, referenceDate);
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + employeeId));
        if (employee.getLocation() == null || employee.getLocation().getCountry() == null) {
            return StatutoryPreviewResponse.builder().note("Employee has no Location/country set").build();
        }
        Optional<EmployeeCompensation> comp = employeeCompensationRepository.findByEmployeeIdAndEffectiveAt(employeeId, window.end());
        if (comp.isEmpty()) {
            return StatutoryPreviewResponse.builder().note("No compensation effective for this employee in this cycle").build();
        }

        List<SalaryStructureComponent> components = salaryStructureComponentRepository.findByStructureId(comp.get().getStructure().getId());
        Map<UUID, BigDecimal> annualByComponentId = salaryStructureResolutionService.resolveAnnualValues(components, comp.get().getCtc());
        BigDecimal grossPay = BigDecimal.ZERO;
        BigDecimal basicAmount = BigDecimal.ZERO;
        for (SalaryStructureComponent sc : components) {
            SalaryComponent catalog = sc.getComponent();
            BigDecimal monthly = annualByComponentId.get(sc.getId())
                    .divide(BigDecimal.valueOf(MONTHS_PER_YEAR), 2, RoundingMode.HALF_UP);
            if (EARNING_TYPES.contains(catalog.getType())) {
                grossPay = grossPay.add(monthly);
            }
            if ("Basic".equalsIgnoreCase(catalog.getName())) {
                basicAmount = monthly;
            }
        }

        Optional<BigDecimal> amount = statutoryDeductionService.previewFormulaAmount(
                employee.getLocation().getCountry(), ruleType, basicAmount, grossPay, window.end());
        return amount.isPresent()
                ? StatutoryPreviewResponse.builder().computedAmount(amount.get()).build()
                : StatutoryPreviewResponse.builder().note("Not configured for this rule, or not eligible this cycle").build();
    }

    @Transactional(readOnly = true)
    public List<StatutoryOverrideResponse> listForEmployee(UUID employeeId) {
        return statutoryDeductionOverrideRepository.findByEmployeeId(employeeId).stream()
                .map(StatutoryOverrideService::toResponse)
                .toList();
    }

    private Map<String, Object> snapshot(StatutoryDeductionOverride r) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("overrideAmount", r.getOverrideAmount());
        snapshot.put("overrideReason", r.getOverrideReason());
        return snapshot;
    }

    private PayCycleWindow resolveCycle(UUID payGroupId, LocalDate referenceDate) {
        PayGroup payGroup = payGroupRepository.findById(payGroupId)
                .orElseThrow(() -> new NoSuchElementException("Pay group not found: " + payGroupId));
        return payGroup.resolveCycle(referenceDate != null ? referenceDate : LocalDate.now());
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private static StatutoryOverrideResponse toResponse(StatutoryDeductionOverride r) {
        return StatutoryOverrideResponse.builder()
                .id(r.getId()).employeeId(r.getEmployeeId()).country(r.getCountry()).ruleType(r.getRuleType())
                .cycleStart(r.getCycleStart()).cycleEnd(r.getCycleEnd()).overrideAmount(r.getOverrideAmount())
                .overrideReason(r.getOverrideReason()).overriddenBy(r.getOverriddenBy()).overriddenAt(r.getOverriddenAt())
                .build();
    }
}
