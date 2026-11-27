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
                           LeaveEligibilityService.Rules eligibility, Limits limits, Accrual accrual, YearEnd yearEnd) {

        /** Without LV-05 year-end settings (no percent cap, no fixed expiry date, no encashment). */
        public TypeRule(UUID ruleId, UUID planId, UUID versionId, UUID leaveTypeId, BigDecimal days,
                        String creditFrequency, boolean carryForwardEnabled, BigDecimal carryForwardMaxDays,
                        Integer carryForwardExpiryMonths, String carryForwardPriority,
                        LeaveEligibilityService.Rules eligibility, Limits limits, Accrual accrual) {
            this(ruleId, planId, versionId, leaveTypeId, days, creditFrequency, carryForwardEnabled, carryForwardMaxDays,
                    carryForwardExpiryMonths, carryForwardPriority, eligibility, limits, accrual, YearEnd.NONE);
        }

        /** Without LV-03 accrual settings (credited at the start of each period, never paused). */
        public TypeRule(UUID ruleId, UUID planId, UUID versionId, UUID leaveTypeId, BigDecimal days,
                        String creditFrequency, boolean carryForwardEnabled, BigDecimal carryForwardMaxDays,
                        Integer carryForwardExpiryMonths, String carryForwardPriority,
                        LeaveEligibilityService.Rules eligibility, Limits limits) {
            this(ruleId, planId, versionId, leaveTypeId, days, creditFrequency, carryForwardEnabled, carryForwardMaxDays,
                    carryForwardExpiryMonths, carryForwardPriority, eligibility, limits, Accrual.DEFAULT);
        }

        /** A rule with no LV-02 limits (quota mode, half-day allowed, nothing else restricted). */
        public TypeRule(UUID ruleId, UUID planId, UUID versionId, UUID leaveTypeId, BigDecimal days,
                        String creditFrequency, boolean carryForwardEnabled, BigDecimal carryForwardMaxDays,
                        Integer carryForwardExpiryMonths, String carryForwardPriority,
                        LeaveEligibilityService.Rules eligibility) {
            this(ruleId, planId, versionId, leaveTypeId, days, creditFrequency, carryForwardEnabled, carryForwardMaxDays,
                    carryForwardExpiryMonths, carryForwardPriority, eligibility, Limits.NONE);
        }

        public boolean unlimited() {
            return com.nforce.onehr.entity.LeavePlanTypeRule.MODE_UNLIMITED.equals(limits.entitlementMode());
        }

        public boolean earnedOnly() {
            return com.nforce.onehr.entity.LeavePlanTypeRule.MODE_EARNED_ONLY.equals(limits.entitlementMode());
        }

        /** Days credited automatically each year: 0 for unlimited or earned-only types. */
        public BigDecimal creditedQuota() {
            return unlimited() || earnedOnly() || days == null ? BigDecimal.ZERO : days;
        }
    }

    /**
     * LV-02: what a request of the type must satisfy (null = no limit). {@code clubbingBlocked}: leave
     * types that may not be taken adjacent to this one.
     */
    public record Limits(String entitlementMode, BigDecimal minDaysPerRequest, BigDecimal maxDaysPerRequest,
                         Integer maxRequestsPerMonth, Integer maxRequestsPerYear, BigDecimal maxConsecutiveDays,
                         Integer noticeDays, BigDecimal noticeAppliesOverDays, boolean allowHalfDay, boolean allowHourly,
                         BigDecimal minHours, BigDecimal attachmentRequiredOverDays, String attachmentAllowedTypes,
                         Integer attachmentMaxMb, java.util.Set<UUID> clubbingBlocked, Sandwich sandwich) {

        public static final Limits NONE = new Limits(com.nforce.onehr.entity.LeavePlanTypeRule.MODE_QUOTA, null, null, null, null,
                null, null, null, true, false, null, null, null, null, java.util.Set.of(), Sandwich.OFF);

        /** Without a sandwich rule (LV-02 shape). */
        public Limits(String entitlementMode, BigDecimal minDaysPerRequest, BigDecimal maxDaysPerRequest,
                      Integer maxRequestsPerMonth, Integer maxRequestsPerYear, BigDecimal maxConsecutiveDays,
                      Integer noticeDays, BigDecimal noticeAppliesOverDays, boolean allowHalfDay, boolean allowHourly,
                      BigDecimal minHours, BigDecimal attachmentRequiredOverDays, String attachmentAllowedTypes,
                      Integer attachmentMaxMb, java.util.Set<UUID> clubbingBlocked) {
            this(entitlementMode, minDaysPerRequest, maxDaysPerRequest, maxRequestsPerMonth, maxRequestsPerYear,
                    maxConsecutiveDays, noticeDays, noticeAppliesOverDays, allowHalfDay, allowHourly, minHours,
                    attachmentRequiredOverDays, attachmentAllowedTypes, attachmentMaxMb, clubbingBlocked, Sandwich.OFF);
        }
    }

    /**
     * LV-07: whether weekly offs / holidays between leave days count. {@code appliesTo}: HOLIDAYS, WEEK_OFFS or
     * BOTH; {@code minDays}: only when the leave covers at least this many working days (null = always).
     */
    public record Sandwich(boolean enabled, String appliesTo, BigDecimal minDays, boolean acrossRequests) {
        public static final Sandwich OFF = new Sandwich(false, com.nforce.onehr.entity.LeavePlanTypeRule.SANDWICH_BOTH, null, false);

        public boolean countsHolidays() {
            return enabled && !com.nforce.onehr.entity.LeavePlanTypeRule.SANDWICH_WEEK_OFFS.equals(appliesTo);
        }

        public boolean countsWeekOffs() {
            return enabled && !com.nforce.onehr.entity.LeavePlanTypeRule.SANDWICH_HOLIDAYS.equals(appliesTo);
        }
    }

    /**
     * LV-03: when a period's days are credited — {@code timing} START (its first day) or END (once it is over) — and
     * when they are withheld: more than {@code pauseLopOverDays} of loss of pay, or more than
     * {@code pauseUnpaidOverDays} of approved unpaid leave, in the period the credit depends on (null = never).
     */
    public record Accrual(String timing, BigDecimal pauseLopOverDays, BigDecimal pauseUnpaidOverDays) {
        public static final Accrual DEFAULT = new Accrual(com.nforce.onehr.entity.LeavePlanTypeRule.TIMING_START, null, null);

        public boolean atEnd() {
            return com.nforce.onehr.entity.LeavePlanTypeRule.TIMING_END.equals(timing);
        }

        public boolean pauses() {
            return pauseLopOverDays != null || pauseUnpaidOverDays != null;
        }
    }

    /**
     * LV-05: year-end settings beyond the carry-forward days cap. {@code carryForwardMaxPercent}: carry at most this %
     * of the unused days; {@code expiryMonth}/{@code expiryDay}: carried days expire on that date of the next year
     * (null = by months, or never); encashment: on/off, max days (null = all the rest), basis BASIC / BASIC_DA and
     * divisor 26 / 30.
     */
    public record YearEnd(BigDecimal carryForwardMaxPercent, Integer expiryMonth, Integer expiryDay, boolean encashEnabled,
                          BigDecimal encashMaxDays, String encashBasis, int encashDivisor) {
        public static final YearEnd NONE = new YearEnd(null, null, null, false, null,
                com.nforce.onehr.entity.LeavePlanTypeRule.ENCASH_BASIC, 30);
    }

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
