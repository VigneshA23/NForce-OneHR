package com.nforce.onehr.service;

import com.nforce.onehr.entity.AssignmentRuleAssignment;
import com.nforce.onehr.entity.LeavePlan;
import com.nforce.onehr.entity.LeavePlanAllocation;
import com.nforce.onehr.entity.LeavePlanTypeClubbing;
import com.nforce.onehr.entity.LeavePlanTypeEligibility;
import com.nforce.onehr.entity.LeavePlanTypeOverride;
import com.nforce.onehr.entity.LeavePlanTypeRule;
import com.nforce.onehr.entity.LeavePlanVersion;
import com.nforce.onehr.repository.AssignmentRuleAssignmentRepository;
import com.nforce.onehr.repository.LeavePlanAllocationRepository;
import com.nforce.onehr.repository.LeavePlanRepository;
import com.nforce.onehr.repository.LeavePlanTypeClubbingRepository;
import com.nforce.onehr.repository.LeavePlanTypeEligibilityRepository;
import com.nforce.onehr.repository.LeavePlanTypeOverrideRepository;
import com.nforce.onehr.repository.LeavePlanTypeRuleRepository;
import com.nforce.onehr.repository.LeavePlanVersionRepository;
import com.nforce.onehr.service.assignment.AssignmentDomain;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LV-01: loads {@link LeavePlanCatalog}s. The plan definitions (plans, versions, type rules, "applies
 * to") are small and read by almost every leave calculation, so they are loaded once per transaction
 * and reused; {@link #invalidate()} drops that copy after a plan is changed in the same transaction.
 * Employee assignments are loaded only for the employees asked for.
 */
@Service
@RequiredArgsConstructor
public class LeavePlanRuleService {

    private static final Object CACHE_KEY = new Object();

    private final LeavePlanRepository planRepository;
    private final LeavePlanVersionRepository versionRepository;
    private final LeavePlanTypeRuleRepository ruleRepository;
    private final LeavePlanTypeEligibilityRepository eligibilityRepository;
    private final LeavePlanTypeOverrideRepository overrideRepository;
    private final LeavePlanTypeClubbingRepository clubbingRepository;
    private final LeavePlanAllocationRepository allocationRepository;
    private final AssignmentRuleAssignmentRepository ruleAssignmentRepository;

    /** The plan definitions only. */
    record Definitions(Map<UUID, LeavePlan> plans, Map<UUID, List<LeavePlanVersion>> versionsByPlan,
                       Map<UUID, Map<UUID, LeavePlanCatalog.TypeRule>> rulesByVersion) {}

    /** Catalog with the assignments of {@code employeeUserIds}. */
    @Transactional(readOnly = true)
    public LeavePlanCatalog catalog(Collection<UUID> employeeUserIds) {
        Definitions d = definitions();
        Set<UUID> ids = new HashSet<>(employeeUserIds);
        Map<UUID, List<LeavePlanAllocation>> allocations = ids.isEmpty() ? Map.of()
                : allocationRepository.findByEmployeeUserIdIn(ids).stream()
                        .collect(Collectors.groupingBy(LeavePlanAllocation::getEmployeeUserId));
        Map<UUID, List<AssignmentRuleAssignment>> byRule = ids.isEmpty() ? Map.of()
                : ruleAssignmentRepository.findByDomainAndEmployeeUserIdIn(AssignmentDomain.LEAVE_PLAN, ids).stream()
                        .collect(Collectors.groupingBy(AssignmentRuleAssignment::getEmployeeUserId));
        return new LeavePlanCatalog(d.plans(), d.versionsByPlan(), d.rulesByVersion(), allocations, byRule, ids);
    }

    /** Catalog with every employee's assignments (batch jobs, bulk screens). */
    @Transactional(readOnly = true)
    public LeavePlanCatalog catalogForAll() {
        Definitions d = definitions();
        Map<UUID, List<LeavePlanAllocation>> allocations = allocationRepository.findAll().stream()
                .collect(Collectors.groupingBy(LeavePlanAllocation::getEmployeeUserId));
        Map<UUID, List<AssignmentRuleAssignment>> byRule = ruleAssignmentRepository.findByDomain(AssignmentDomain.LEAVE_PLAN).stream()
                .collect(Collectors.groupingBy(AssignmentRuleAssignment::getEmployeeUserId));
        return new LeavePlanCatalog(d.plans(), d.versionsByPlan(), d.rulesByVersion(), allocations, byRule, null);
    }

    /** The type's rules in the employee's plan on {@code date} (eligibility not applied). */
    @Transactional(readOnly = true)
    public Optional<LeavePlanCatalog.TypeRule> ruleFor(UUID employeeUserId, UUID leaveTypeId, LocalDate date) {
        return catalog(List.of(employeeUserId)).ruleFor(employeeUserId, leaveTypeId, date);
    }

    /** Drops this transaction's cached plan definitions, after a plan or version was written. */
    public void invalidate() {
        if (TransactionSynchronizationManager.hasResource(CACHE_KEY)) {
            TransactionSynchronizationManager.unbindResource(CACHE_KEY);
        }
    }

    private Definitions definitions() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return load();
        Definitions cached = (Definitions) TransactionSynchronizationManager.getResource(CACHE_KEY);
        if (cached != null) return cached;
        Definitions loaded = load();
        TransactionSynchronizationManager.bindResource(CACHE_KEY, loaded);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(CACHE_KEY);
            }
        });
        return loaded;
    }

    private Definitions load() {
        Map<UUID, LeavePlan> plans = new HashMap<>();
        planRepository.findAll().forEach(p -> plans.put(p.getId(), p));
        List<LeavePlanVersion> versions = versionRepository.findAll();
        Map<UUID, List<LeavePlanVersion>> versionsByPlan = versions.stream()
                .collect(Collectors.groupingBy(LeavePlanVersion::getPlanId));
        Map<UUID, UUID> planOfVersion = new HashMap<>();
        versions.forEach(v -> planOfVersion.put(v.getId(), v.getPlanId()));

        List<LeavePlanTypeRule> rules = ruleRepository.findAll();
        Set<UUID> ruleIds = rules.stream().map(LeavePlanTypeRule::getId).collect(Collectors.toSet());
        Map<UUID, List<LeavePlanTypeEligibility>> eligibility = ruleIds.isEmpty() ? Map.of()
                : eligibilityRepository.findByRuleIdIn(ruleIds).stream().collect(Collectors.groupingBy(LeavePlanTypeEligibility::getRuleId));
        Map<UUID, List<LeavePlanTypeOverride>> overrides = ruleIds.isEmpty() ? Map.of()
                : overrideRepository.findByRuleIdIn(ruleIds).stream().collect(Collectors.groupingBy(LeavePlanTypeOverride::getRuleId));
        Map<UUID, Set<UUID>> clubbing = new HashMap<>();
        if (!ruleIds.isEmpty()) {
            for (LeavePlanTypeClubbing c : clubbingRepository.findByRuleIdIn(ruleIds)) {
                clubbing.computeIfAbsent(c.getRuleId(), k -> new HashSet<>()).add(c.getBlockedLeaveTypeId());
            }
        }

        Map<UUID, Map<UUID, LeavePlanCatalog.TypeRule>> byVersion = new HashMap<>();
        for (LeavePlanTypeRule r : rules) {
            LeavePlanCatalog.TypeRule tr = toTypeRule(r, planOfVersion.get(r.getVersionId()),
                    eligibility.getOrDefault(r.getId(), List.of()), overrides.getOrDefault(r.getId(), List.of()),
                    clubbing.getOrDefault(r.getId(), Set.of()));
            byVersion.computeIfAbsent(r.getVersionId(), k -> new HashMap<>()).put(r.getLeaveTypeId(), tr);
        }
        return new Definitions(plans, versionsByPlan, byVersion);
    }

    static LeavePlanCatalog.TypeRule toTypeRule(LeavePlanTypeRule r, UUID planId, List<LeavePlanTypeEligibility> eligibility,
                                                List<LeavePlanTypeOverride> overrides, Set<UUID> clubbingBlocked) {
        return new LeavePlanCatalog.TypeRule(r.getId(), planId, r.getVersionId(), r.getLeaveTypeId(), r.getDays(),
                r.getCreditFrequency(), r.isCarryForwardEnabled(), r.getCarryForwardMaxDays(), r.getCarryForwardExpiryMonths(),
                r.getCarryForwardPriority(), eligibilityRules(eligibility, overrides), limitsOf(r, clubbingBlocked));
    }

    static LeavePlanCatalog.Limits limitsOf(LeavePlanTypeRule r, Set<UUID> clubbingBlocked) {
        return new LeavePlanCatalog.Limits(r.getEntitlementMode() == null ? LeavePlanTypeRule.MODE_QUOTA : r.getEntitlementMode(),
                r.getMinDaysPerRequest(), r.getMaxDaysPerRequest(), r.getMaxRequestsPerMonth(), r.getMaxRequestsPerYear(),
                r.getMaxConsecutiveDays(), r.getNoticeDays(), r.getNoticeAppliesOverDays(), r.isAllowHalfDay(), r.isAllowHourly(),
                r.getMinHours(), r.getAttachmentRequiredOverDays(), r.getAttachmentAllowedTypes(), r.getAttachmentMaxMb(),
                Set.copyOf(clubbingBlocked), new LeavePlanCatalog.Sandwich(r.isSandwichEnabled(),
                        r.getSandwichAppliesTo() == null ? LeavePlanTypeRule.SANDWICH_BOTH : r.getSandwichAppliesTo(),
                        r.getSandwichMinDays(), r.isSandwichAcrossRequests()));
    }

    /** The leave types a rule blocks from being taken adjacent to it (the plan editor). */
    @Transactional(readOnly = true)
    public List<UUID> clubbingOf(UUID ruleId) {
        return clubbingRepository.findByRuleIdIn(List.of(ruleId)).stream().map(LeavePlanTypeClubbing::getBlockedLeaveTypeId).toList();
    }

    static LeaveEligibilityService.Rules eligibilityRules(List<LeavePlanTypeEligibility> eligibility,
                                                         List<LeavePlanTypeOverride> overrides) {
        if (eligibility.isEmpty() && overrides.isEmpty()) return LeaveEligibilityService.Rules.EVERYONE;
        Set<String> genders = new HashSet<>(), departments = new HashSet<>(), locations = new HashSet<>(), types = new HashSet<>();
        for (LeavePlanTypeEligibility e : eligibility) {
            switch (e.getRuleType()) {
                case LeavePlanTypeEligibility.GENDER -> genders.add(LeaveEligibilityService.norm(e.getValue()));
                case LeavePlanTypeEligibility.DEPARTMENT -> departments.add(e.getValue());
                case LeavePlanTypeEligibility.LOCATION -> locations.add(e.getValue());
                case LeavePlanTypeEligibility.EMPLOYMENT_TYPE -> types.add(LeaveEligibilityService.norm(e.getValue()));
                default -> { }
            }
        }
        Set<UUID> include = new HashSet<>(), exclude = new HashSet<>();
        for (LeavePlanTypeOverride o : overrides) {
            (LeavePlanTypeOverride.INCLUDE.equals(o.getMode()) ? include : exclude).add(o.getEmployeeUserId());
        }
        return new LeaveEligibilityService.Rules(genders, departments, locations, types, include, exclude);
    }

    /** The display form of one rule's "applies to" values, keeping the stored spelling ("Female", "FULL_TIME"). */
    @Transactional(readOnly = true)
    public com.nforce.onehr.dto.LeaveConfigDtos.EligibilityConfig eligibilityConfig(UUID ruleId) {
        List<String> genders = new ArrayList<>(), departments = new ArrayList<>(), locations = new ArrayList<>(), types = new ArrayList<>();
        for (LeavePlanTypeEligibility e : eligibilityRepository.findByRuleIdIn(List.of(ruleId))) {
            switch (e.getRuleType()) {
                case LeavePlanTypeEligibility.GENDER -> genders.add(e.getValue());
                case LeavePlanTypeEligibility.DEPARTMENT -> departments.add(e.getValue());
                case LeavePlanTypeEligibility.LOCATION -> locations.add(e.getValue());
                case LeavePlanTypeEligibility.EMPLOYMENT_TYPE -> types.add(e.getValue());
                default -> { }
            }
        }
        List<UUID> include = new ArrayList<>(), exclude = new ArrayList<>();
        for (LeavePlanTypeOverride o : overrideRepository.findByRuleIdIn(List.of(ruleId))) {
            (LeavePlanTypeOverride.INCLUDE.equals(o.getMode()) ? include : exclude).add(o.getEmployeeUserId());
        }
        genders.sort(null);
        departments.sort(null);
        locations.sort(null);
        types.sort(null);
        return new com.nforce.onehr.dto.LeaveConfigDtos.EligibilityConfig(genders, departments, locations, types, include, exclude);
    }
}
