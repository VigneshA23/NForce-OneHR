package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreateStatutoryOverrideRequest;
import com.nforce.onehr.dto.payroll.StatutoryOverrideResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PayCycleWindow;
import com.nforce.onehr.entity.PayGroup;
import com.nforce.onehr.entity.StatutoryDeductionOverride;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayGroupRepository;
import com.nforce.onehr.repository.StatutoryDeductionOverrideRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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

    private final StatutoryDeductionOverrideRepository statutoryDeductionOverrideRepository;
    private final PayGroupRepository payGroupRepository;
    private final EmployeeRepository employeeRepository;
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
