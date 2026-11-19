package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.CreateRecurringAdHocPaymentRuleRequest;
import com.nforce.onehr.dto.payroll.RecurringAdHocPaymentRuleResponse;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C24 follow-up (ONEHR-579) — CRUD for {@link RecurringAdHocPaymentRule}. Applying an active
 * rule to a generated run lives in {@code PayrollRunService#generateDraft}, not here: this service
 * only owns the rule's own lifecycle (create/list/activate/deactivate), same split {@code
 * StatutoryRuleService} (config) vs {@code StatutoryDeductionService} (application) already uses.
 */
@Service
@RequiredArgsConstructor
public class RecurringAdHocPaymentRuleService {

    private final RecurringAdHocPaymentRuleRepository recurringAdHocPaymentRuleRepository;
    private final PayGroupRepository payGroupRepository;
    private final SalaryComponentRepository salaryComponentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;

    @Transactional
    public RecurringAdHocPaymentRuleResponse create(CreateRecurringAdHocPaymentRuleRequest req, String actorEmail) {
        if (req.getPayGroupId() != null && !payGroupRepository.existsById(req.getPayGroupId())) {
            throw new NoSuchElementException("Pay group not found: " + req.getPayGroupId());
        }
        SalaryComponent catalog = salaryComponentRepository.findByNameIgnoreCase(req.getComponentName())
                .orElseThrow(() -> new NoSuchElementException("Unknown salary component: " + req.getComponentName()));
        if (!catalog.isActive()) {
            throw new IllegalArgumentException("Salary component '" + catalog.getName() + "' is not active");
        }
        if (!SalaryComponentType.AD_HOC_EARNING.equals(catalog.getType())) {
            throw new IllegalArgumentException(
                    "'" + catalog.getName() + "' is a " + catalog.getType() + " component — recurring rules only accept AD_HOC_EARNING");
        }

        User actor = requireActor(actorEmail);
        RecurringAdHocPaymentRule rule = recurringAdHocPaymentRuleRepository.save(RecurringAdHocPaymentRule.builder()
                .payGroupId(req.getPayGroupId())
                .componentId(catalog.getId())
                .componentName(catalog.getName())
                .componentType(catalog.getType())
                .amount(req.getAmount())
                .reason(req.getReason().trim())
                .active(true)
                .createdBy(actor.getId())
                .build());

        auditService.log(actor.getId(), "PAYROLL_RECURRING_ADHOC_RULE_CREATED", rule.getId(), null, auditSnapshot.toJson(ruleSnapshot(rule)));
        return toResponses(List.of(rule)).get(0);
    }

    @Transactional
    public RecurringAdHocPaymentRuleResponse setActive(UUID id, boolean active, String actorEmail) {
        RecurringAdHocPaymentRule rule = recurringAdHocPaymentRuleRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Recurring ad-hoc payment rule not found: " + id));
        User actor = requireActor(actorEmail);
        String before = auditSnapshot.toJson(ruleSnapshot(rule));
        rule.setActive(active);
        rule = recurringAdHocPaymentRuleRepository.save(rule);
        auditService.log(actor.getId(), active ? "PAYROLL_RECURRING_ADHOC_RULE_ACTIVATED" : "PAYROLL_RECURRING_ADHOC_RULE_DEACTIVATED",
                rule.getId(), before, auditSnapshot.toJson(ruleSnapshot(rule)));
        return toResponses(List.of(rule)).get(0);
    }

    @Transactional(readOnly = true)
    public List<RecurringAdHocPaymentRuleResponse> list() {
        return toResponses(recurringAdHocPaymentRuleRepository.findAllOrdered());
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Map<String, Object> ruleSnapshot(RecurringAdHocPaymentRule r) {
        return Map.of(
                "payGroupId", r.getPayGroupId() == null ? "ALL" : r.getPayGroupId(),
                "componentName", r.getComponentName(),
                "amount", r.getAmount(),
                "active", r.isActive());
    }

    private List<RecurringAdHocPaymentRuleResponse> toResponses(List<RecurringAdHocPaymentRule> rules) {
        Map<UUID, String> payGroupNames = payGroupRepository.findAllById(
                rules.stream().map(RecurringAdHocPaymentRule::getPayGroupId).filter(id -> id != null).collect(Collectors.toSet())
        ).stream().collect(Collectors.toMap(PayGroup::getId, PayGroup::getName));

        Set<UUID> createdByIds = rules.stream().map(RecurringAdHocPaymentRule::getCreatedBy).collect(Collectors.toSet());
        Map<UUID, String> namesById = new HashMap<>();
        if (!createdByIds.isEmpty()) {
            for (Employee e : employeeRepository.findAllById(createdByIds)) {
                namesById.put(e.getUserId(), e.getFullName());
            }
            Set<UUID> remaining = new HashSet<>(createdByIds);
            remaining.removeAll(namesById.keySet());
            if (!remaining.isEmpty()) {
                for (User u : userRepository.findAllById(remaining)) {
                    namesById.put(u.getId(), u.getEmail());
                }
            }
        }

        return rules.stream().map(r -> RecurringAdHocPaymentRuleResponse.builder()
                .id(r.getId())
                .payGroupId(r.getPayGroupId())
                .payGroupName(r.getPayGroupId() == null ? "All Pay Groups" : payGroupNames.getOrDefault(r.getPayGroupId(), "Unknown"))
                .componentName(r.getComponentName())
                .componentType(r.getComponentType())
                .amount(r.getAmount())
                .reason(r.getReason())
                .active(r.isActive())
                .createdByName(namesById.getOrDefault(r.getCreatedBy(), "Unknown"))
                .createdAt(r.getCreatedAt())
                .build()).collect(Collectors.toList());
    }
}
