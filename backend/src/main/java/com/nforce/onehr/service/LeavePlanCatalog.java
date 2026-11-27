package com.nforce.onehr.service;

import com.nforce.onehr.entity.AssignmentRuleAssignment;
import com.nforce.onehr.entity.LeavePlan;
import com.nforce.onehr.entity.LeavePlanAllocation;
import com.nforce.onehr.entity.LeavePlanVersion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * LV-01: an in-memory snapshot of the leave plans and of some employees' plan assignments, answering
 * the two questions every leave calculation asks:
 * <ul>
 *   <li>{@link #planOf}: which plan an employee is on for a date — explicit allocation, else the
 *       assignment rule's outcome, else the org default (inactive plans are skipped at every step, the
 *       same order as penalization policy resolution);</li>
 *   <li>{@link #rule}: a leave type's rules in that plan on that date (the version in effect), or empty
 *       when the plan doesn't include the type.</li>
 * </ul>
 * Pure: built by {@link LeavePlanRuleService}, or directly in tests.
 */
public final class LeavePlanCatalog {

    public static final String SOURCE_ALLOCATION = "ALLOCATION";
    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_DEFAULT = "DEFAULT";

    /** One leave type's rules inside one plan version. {@code days} is null for unpaid (unlimited) types. */
    public record TypeRule(UUID ruleId, UUID planId, UUID versionId, UUID leaveTypeId, BigDecimal days,
                           String creditFrequency, boolean carryForwardEnabled, BigDecimal carryForwardMaxDays,
                           Integer carryForwardExpiryMonths, String carryForwardPriority,
                           LeaveEligibilityService.Rules eligibility) {}

    /** The plan an employee is on for a date, and why. {@code ruleName} only for {@link #SOURCE_RULE}. */
    public record PlanResolution(UUID planId, String source, String ruleName) {}

    private final Map<UUID, LeavePlan> plans;
    private final Map<UUID, List<LeavePlanVersion>> versionsByPlan;
    private final Map<UUID, Map<UUID, TypeRule>> rulesByVersion;
    private final Map<UUID, List<LeavePlanAllocation>> allocationsByEmployee;
    private final Map<UUID, List<AssignmentRuleAssignment>> ruleAssignmentsByEmployee;
    /** Employees whose assignments were loaded; null = every employee. */
    private final java.util.Set<UUID> loadedEmployees;

    /**
     * @param versionsByPlan        each plan's versions (any order)
     * @param rulesByVersion        version id → leave type id → rule
     * @param allocationsByEmployee explicit allocations of the loaded employees
     * @param ruleAssignmentsByEmployee LEAVE_PLAN assignment-rule outcomes of the loaded employees
     * @param loadedEmployees       the employees the two maps were loaded for (null = all)
     */
    public LeavePlanCatalog(Map<UUID, LeavePlan> plans, Map<UUID, List<LeavePlanVersion>> versionsByPlan,
                            Map<UUID, Map<UUID, TypeRule>> rulesByVersion,
                            Map<UUID, List<LeavePlanAllocation>> allocationsByEmployee,
                            Map<UUID, List<AssignmentRuleAssignment>> ruleAssignmentsByEmployee,
                            java.util.Set<UUID> loadedEmployees) {
        this.plans = plans;
        this.versionsByPlan = new java.util.HashMap<>();
        versionsByPlan.forEach((planId, versions) -> this.versionsByPlan.put(planId,
                versions.stream().sorted(Comparator.comparing(LeavePlanVersion::getEffectiveFrom)).toList()));
        this.rulesByVersion = rulesByVersion;
        this.allocationsByEmployee = allocationsByEmployee;
        this.ruleAssignmentsByEmployee = ruleAssignmentsByEmployee;
        this.loadedEmployees = loadedEmployees;
    }

    public Optional<LeavePlan> plan(UUID planId) {
        return Optional.ofNullable(planId == null ? null : plans.get(planId));
    }

    public java.util.Collection<LeavePlan> plans() {
        return plans.values();
    }

    public Optional<UUID> defaultPlanId() {
        return plans.values().stream().filter(p -> p.isOrgDefault() && p.isActive()).map(LeavePlan::getId).findFirst();
    }

    /** Explicit allocation → rule outcome → org default; inactive plans are skipped. Null when nothing applies. */
    public PlanResolution planOf(UUID employeeUserId, LocalDate date) {
        if (loadedEmployees != null && !loadedEmployees.contains(employeeUserId)) {
            throw new IllegalStateException("Plan assignments of employee " + employeeUserId + " were not loaded");
        }
        for (LeavePlanAllocation a : allocationsByEmployee.getOrDefault(employeeUserId, List.of())) {
            if (a.covers(date) && usable(a.getPlanId())) return new PlanResolution(a.getPlanId(), SOURCE_ALLOCATION, null);
        }
        for (AssignmentRuleAssignment r : ruleAssignmentsByEmployee.getOrDefault(employeeUserId, List.of())) {
            boolean covers = !date.isBefore(r.getEffectiveFrom()) && (r.getEffectiveTo() == null || !date.isAfter(r.getEffectiveTo()));
            if (covers && usable(r.getTargetId())) return new PlanResolution(r.getTargetId(), SOURCE_RULE, r.getRuleName());
        }
        return defaultPlanId().map(id -> new PlanResolution(id, SOURCE_DEFAULT, null)).orElse(null);
    }

    private boolean usable(UUID planId) {
        LeavePlan p = plans.get(planId);
        return p != null && p.isActive();
    }

    /** The version of {@code planId} in effect on {@code date}; dates before the first version use the first. */
    public Optional<LeavePlanVersion> versionAt(UUID planId, LocalDate date) {
        List<LeavePlanVersion> versions = versionsByPlan.getOrDefault(planId, List.of());
        if (versions.isEmpty()) return Optional.empty();
        for (LeavePlanVersion v : versions) {
            if (v.covers(date)) return Optional.of(v);
        }
        return date.isBefore(versions.get(0).getEffectiveFrom()) ? Optional.of(versions.get(0)) : Optional.empty();
    }

    public List<LeavePlanVersion> versions(UUID planId) {
        return versionsByPlan.getOrDefault(planId, List.of());
    }

    /** The type's rules in {@code planId} on {@code date}; empty when that version doesn't include the type. */
    public Optional<TypeRule> rule(UUID planId, UUID leaveTypeId, LocalDate date) {
        if (planId == null) return Optional.empty();
        return versionAt(planId, date).map(v -> rulesByVersion.getOrDefault(v.getId(), Map.of()).get(leaveTypeId));
    }

    /** Every type rule of one version, keyed by leave type. */
    public Map<UUID, TypeRule> rulesOfVersion(UUID versionId) {
        return rulesByVersion.getOrDefault(versionId, Map.of());
    }

    /** The type's rules in the employee's plan on {@code date} (ignores "applies to" — see {@link TypeRule#eligibility}). */
    public Optional<TypeRule> ruleFor(UUID employeeUserId, UUID leaveTypeId, LocalDate date) {
        PlanResolution p = planOf(employeeUserId, date);
        return p == null ? Optional.empty() : rule(p.planId(), leaveTypeId, date);
    }
}
