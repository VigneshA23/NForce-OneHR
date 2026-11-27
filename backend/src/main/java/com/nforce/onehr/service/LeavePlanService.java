package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeaveConfigDtos.EligibilityConfig;
import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.dto.LeavePlanDtos.ClonePlanRequest;
import com.nforce.onehr.dto.LeavePlanDtos.CreatePlanRequest;
import com.nforce.onehr.dto.LeavePlanDtos.PlanDetail;
import com.nforce.onehr.dto.LeavePlanDtos.PlanSummary;
import com.nforce.onehr.dto.LeavePlanDtos.SaveVersionRequest;
import com.nforce.onehr.dto.LeavePlanDtos.AccrualSettings;
import com.nforce.onehr.dto.LeavePlanDtos.RuleLimits;
import com.nforce.onehr.dto.LeavePlanDtos.TypeRuleDto;
import com.nforce.onehr.dto.LeavePlanDtos.TypeRuleRequest;
import com.nforce.onehr.dto.LeavePlanDtos.UpdatePlanRequest;
import com.nforce.onehr.dto.LeavePlanDtos.VersionDetail;
import com.nforce.onehr.dto.LeavePlanDtos.VersionSummary;
import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeavePlan;
import com.nforce.onehr.entity.LeavePlanTypeEligibility;
import com.nforce.onehr.entity.LeavePlanTypeOverride;
import com.nforce.onehr.entity.LeavePlanTypeClubbing;
import com.nforce.onehr.entity.LeavePlanTypeRule;
import com.nforce.onehr.entity.LeavePlanVersion;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AssignmentRuleAssignmentRepository;
import com.nforce.onehr.repository.AssignmentRuleRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeavePlanAllocationRepository;
import com.nforce.onehr.repository.LeavePlanRepository;
import com.nforce.onehr.repository.LeavePlanTypeEligibilityRepository;
import com.nforce.onehr.repository.LeavePlanTypeOverrideRepository;
import com.nforce.onehr.repository.LeavePlanTypeClubbingRepository;
import com.nforce.onehr.repository.LeavePlanTypeRuleRepository;
import com.nforce.onehr.repository.LeavePlanVersionRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.assignment.AssignmentDomain;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LV-01: Leave Plans (Organization Masters > Leave > Leave Plans, permission {@code leave_configuration}).
 *
 * <p>A plan is a header (name, description, leave-year start month, active, org default) plus
 * effective-dated <b>versions</b>, each holding the plan's leave types and their rules — the
 * policy → version pattern of penalization policies. Saving rules creates a version from a chosen date
 * (or replaces the one starting that day); versions never overlap, earlier and later versions are left
 * as they are, and closed leave years can't be changed. A save that changes nothing creates nothing.
 *
 * <p>Every change is audited. Changes that move employees between plans (deactivate, set default) and
 * rule changes saved with "re-credit" re-credit the months already credited ({@link LeaveAccrualService#reconcile}).
 */
@Service
@RequiredArgsConstructor
public class LeavePlanService {

    public static final String STATUS_PAST = "PAST";
    public static final String STATUS_CURRENT = "CURRENT";
    public static final String STATUS_SCHEDULED = "SCHEDULED";

    private final LeavePlanRepository planRepository;
    private final LeavePlanVersionRepository versionRepository;
    private final LeavePlanTypeRuleRepository ruleRepository;
    private final LeavePlanTypeClubbingRepository clubbingRepository;
    private final LeavePlanTypeEligibilityRepository eligibilityRepository;
    private final LeavePlanTypeOverrideRepository overrideRepository;
    private final LeavePlanAllocationRepository allocationRepository;
    private final AssignmentRuleRepository assignmentRuleRepository;
    private final AssignmentRuleAssignmentRepository ruleAssignmentRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final LeavePlanRuleService planRuleService;
    private final LeaveAccrualService accrualService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final AttendanceProperties attendanceProperties;

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PlanSummary> list() {
        LocalDate today = today();
        LeavePlanCatalog catalog = planRuleService.catalogForAll();
        Map<UUID, Long> counts = new HashMap<>();
        for (LeaveEmployeeProfile p : employeeRepository.findLeaveProfiles()) {
            if (!p.employed()) continue;
            LeavePlanCatalog.PlanResolution r = catalog.planOf(p.userId(), today);
            if (r != null) counts.merge(r.planId(), 1L, Long::sum);
        }
        return planRepository.findAllByOrderByNameAsc().stream()
                .map(p -> summary(p, catalog, today, counts.getOrDefault(p.getId(), 0L)))
                .sorted(Comparator.comparing((PlanSummary s) -> !s.orgDefault()).thenComparing(s -> s.name().toLowerCase()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PlanDetail detail(UUID planId) {
        LeavePlan plan = requirePlan(planId);
        List<PlanSummary> all = list();
        PlanSummary summary = all.stream().filter(s -> s.id().equals(planId)).findFirst().orElseThrow();
        List<LeavePlanVersion> versions = versionRepository.findByPlanIdOrderByEffectiveFromAsc(plan.getId());
        Map<UUID, String> names = userNames(versions.stream().map(LeavePlanVersion::getCreatedBy).filter(Objects::nonNull).collect(Collectors.toSet()));
        LocalDate today = today();
        List<VersionSummary> out = versions.stream().map(v -> versionSummary(v, today, names)).toList();
        return new PlanDetail(summary, out);
    }

    /** One version's leave types and rules. */
    @Transactional(readOnly = true)
    public VersionDetail version(UUID versionId) {
        LeavePlanVersion v = versionRepository.findById(versionId)
                .orElseThrow(() -> new NoSuchElementException("Leave plan version not found"));
        LeavePlan plan = requirePlan(v.getPlanId());
        Map<UUID, LeaveType> types = leaveTypeRepository.findAll().stream().collect(Collectors.toMap(LeaveType::getId, Function.identity()));
        List<TypeRuleDto> rules = ruleRepository.findByVersionId(v.getId()).stream()
                .filter(r -> types.containsKey(r.getLeaveTypeId()))
                .map(r -> toDto(r, types.get(r.getLeaveTypeId())))
                .sorted(Comparator.comparing(TypeRuleDto::leaveTypeName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Map<UUID, String> names = userNames(v.getCreatedBy() == null ? Set.of() : Set.of(v.getCreatedBy()));
        return new VersionDetail(plan.getId(), plan.getName(), versionSummary(v, today(), names), rules);
    }

    /** The version in effect today (or the first one, before any is in effect). */
    @Transactional(readOnly = true)
    public VersionDetail currentVersion(UUID planId) {
        requirePlan(planId);
        LeavePlanVersion v = planRuleService.catalog(List.of()).versionAt(planId, today())
                .orElseThrow(() -> new NoSuchElementException("This plan has no version in effect"));
        return version(v.getId());
    }

    // ── Plans ───────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public PlanDetail create(CreatePlanRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        String name = req.name().trim();
        if (planRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalArgumentException("A leave plan named \"" + name + "\" already exists");
        }
        requireJanuary(req.leaveYearStartMonth());
        Map<UUID, LeaveType> types = validateRules(req.rules());
        LeavePlan plan = LeavePlan.builder()
                .name(name)
                .description(blankToNull(req.description()))
                .createdBy(actor.getId())
                .build();
        applyProration(plan, req.proration());
        plan = planRepository.save(plan);
        // The first version covers the whole current leave year, so the plan works for any assignment from now on.
        LeavePlanVersion v = versionRepository.save(LeavePlanVersion.builder()
                .planId(plan.getId()).version(1)
                .effectiveFrom(LeaveBalanceService.yearStart(LeaveBalanceService.leaveYearOf(today())))
                .createdBy(actor.getId()).build());
        writeRules(v.getId(), req.rules(), types);
        planRuleService.invalidate();
        auditService.log(actor.getId(), "LEAVE_PLAN_CREATED", plan.getId(), null,
                auditSnapshot.toJson(planSnapshot(plan, rulesSnapshot(v.getId()))));
        return detail(plan.getId());
    }

    @Transactional
    public PlanDetail update(UUID planId, UpdatePlanRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeavePlan plan = requirePlan(planId);
        String name = req.name().trim();
        if (planRepository.existsByNameIgnoreCaseAndIdNot(name, planId)) {
            throw new IllegalArgumentException("A leave plan named \"" + name + "\" already exists");
        }
        requireJanuary(req.leaveYearStartMonth());
        String before = auditSnapshot.toJson(planSnapshot(plan, null));
        LeaveAccrualService.Proration prorationBefore = LeaveAccrualService.Proration.of(plan);
        boolean changed = !plan.getName().equals(name) || !Objects.equals(plan.getDescription(), blankToNull(req.description()));
        plan.setName(name);
        plan.setDescription(blankToNull(req.description()));
        applyProration(plan, req.proration());
        boolean prorationChanged = !prorationBefore.equals(LeaveAccrualService.Proration.of(plan));
        if (changed || prorationChanged) {
            planRepository.save(plan);
            planRuleService.invalidate();
            auditService.log(actor.getId(), "LEAVE_PLAN_UPDATED", plan.getId(), before, auditSnapshot.toJson(planSnapshot(plan, null)));
        }
        if (prorationChanged) {
            // Joining / exit months already credited this year follow the new proration (other months are unchanged).
            accrualService.reconcile(null, LeaveBalanceService.yearStart(LeaveBalanceService.leaveYearOf(today())), false);
        }
        return detail(planId);
    }

    /** A new plan with the rules of {@code planId}'s version in effect today; no assignments are copied. */
    @Transactional
    public PlanDetail clone(UUID planId, ClonePlanRequest req, String actorEmail) {
        requirePlan(planId);
        VersionDetail source = currentVersion(planId);
        List<TypeRuleRequest> rules = source.rules().stream().map(r -> new TypeRuleRequest(r.leaveTypeId(), r.days(),
                r.creditFrequency(), r.carryForwardEnabled(), r.carryForwardMaxDays(), r.carryForwardExpiryMonths(),
                r.carryForwardPriority(), r.eligibility(), r.limits(), r.accrual())).toList();
        LeavePlan from = requirePlan(planId);
        PlanDetail created = create(new CreatePlanRequest(req.name(), from.getDescription(), from.getLeaveYearStartMonth(), rules,
                prorationDto(from)), actorEmail);
        User actor = requireActor(actorEmail);
        auditService.log(actor.getId(), "LEAVE_PLAN_CLONED", created.plan().id(), null,
                auditSnapshot.toJson(Map.of("clonedFrom", from.getName(), "name", created.plan().name())));
        return created;
    }

    /** Inactive plans are skipped when resolving: their employees move to their rule's plan or the org default. */
    @Transactional
    public PlanDetail setActive(UUID planId, boolean active, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeavePlan plan = requirePlan(planId);
        if (!active && plan.isOrgDefault()) {
            throw new IllegalArgumentException("\"" + plan.getName() + "\" is the organization default plan and can't be "
                    + "deactivated. Make another plan the default first.");
        }
        if (plan.isActive() != active) {
            plan.setStatus(active ? LeavePlan.ACTIVE : LeavePlan.INACTIVE);
            planRepository.save(plan);
            planRuleService.invalidate();
            auditService.log(actor.getId(), "LEAVE_PLAN_STATUS_CHANGED", plan.getId(),
                    auditSnapshot.toJson(Map.of("name", plan.getName(), "status", active ? LeavePlan.INACTIVE : LeavePlan.ACTIVE)),
                    auditSnapshot.toJson(Map.of("name", plan.getName(), "status", plan.getStatus())));
            accrualService.reconcile(null, today(), false);
        }
        return detail(planId);
    }

    /** The plan of every employee with no allocation and no matching rule (new joiners included). */
    @Transactional
    public PlanDetail setDefault(UUID planId, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeavePlan plan = requirePlan(planId);
        if (!plan.isActive()) {
            throw new IllegalArgumentException("Only an active plan can be the organization default.");
        }
        if (!plan.isOrgDefault()) {
            String previous = planRepository.findFirstByOrgDefaultTrue().map(LeavePlan::getName).orElse(null);
            planRepository.clearOrgDefault();
            plan = requirePlan(planId);
            plan.setOrgDefault(true);
            planRepository.save(plan);
            planRuleService.invalidate();
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("defaultPlan", previous);
            auditService.log(actor.getId(), "LEAVE_PLAN_SET_AS_DEFAULT", plan.getId(), auditSnapshot.toJson(before),
                    auditSnapshot.toJson(Map.of("defaultPlan", plan.getName())));
            accrualService.reconcile(null, today(), false);
        }
        return detail(planId);
    }

    /** Only a plan that never governed anyone can be deleted; otherwise deactivate it. */
    @Transactional
    public void delete(UUID planId, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeavePlan plan = requirePlan(planId);
        if (plan.isOrgDefault()) {
            throw new IllegalArgumentException("\"" + plan.getName() + "\" is the organization default plan and can't be deleted.");
        }
        if (allocationRepository.countByPlanId(planId) > 0
                || assignmentRuleRepository.existsByDomainAndTargetId(AssignmentDomain.LEAVE_PLAN, planId)
                || ruleAssignmentRepository.existsByDomainAndTargetId(AssignmentDomain.LEAVE_PLAN, planId)) {
            throw new IllegalStateException("\"" + plan.getName() + "\" has been assigned to employees (directly or by a rule), "
                    + "so it can't be deleted. Deactivate it instead.");
        }
        String before = auditSnapshot.toJson(planSnapshot(plan, null));
        planRepository.delete(plan); // versions, rules and "applies to" go with it (ON DELETE CASCADE)
        planRuleService.invalidate();
        auditService.log(actor.getId(), "LEAVE_PLAN_DELETED", planId, before, null);
    }

    // ── Versions ────────────────────────────────────────────────────────────────────────────────

    /**
     * The plan's leave types and rules from {@code effectiveFrom}. A version already starting that day is
     * replaced; otherwise a new version starts then and runs until the next version (or open-ended), and
     * the version it starts inside now ends the day before. Unchanged rules create nothing.
     */
    @Transactional
    public PlanDetail saveVersion(UUID planId, SaveVersionRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeavePlan plan = requirePlan(planId);
        LocalDate from = req.effectiveFrom();
        int currentYear = LeaveBalanceService.leaveYearOf(today());
        if (from.isBefore(LeaveBalanceService.yearStart(currentYear))) {
            throw new IllegalArgumentException("Rules can't take effect before 1 Jan " + currentYear
                    + ": earlier leave years are closed.");
        }
        Map<UUID, LeaveType> types = validateRules(req.rules());
        List<LeavePlanVersion> versions = versionRepository.findByPlanIdOrderByEffectiveFromAsc(planId);
        Optional<LeavePlanVersion> sameDay = versions.stream().filter(v -> v.getEffectiveFrom().equals(from)).findFirst();
        Optional<LeavePlanVersion> covering = versions.stream().filter(v -> v.covers(from)).findFirst();

        String newRules = rulesSnapshotOf(req.rules());
        String beforeRules = covering.map(v -> rulesSnapshotOf(requestsOf(v.getId()))).orElse(null);
        if (newRules.equals(beforeRules)) {
            return detail(planId); // nothing changed
        }

        LeavePlanVersion target;
        if (sameDay.isPresent()) {
            target = sameDay.get();
            ruleRepository.deleteAll(ruleRepository.findByVersionId(target.getId()));
            ruleRepository.flush();
        } else {
            LocalDate nextStart = versions.stream().map(LeavePlanVersion::getEffectiveFrom).filter(d -> d.isAfter(from))
                    .min(Comparator.naturalOrder()).orElse(null);
            covering.ifPresent(c -> {
                c.setEffectiveTo(from.minusDays(1));
                versionRepository.saveAndFlush(c);
            });
            int number = versions.stream().mapToInt(LeavePlanVersion::getVersion).max().orElse(0) + 1;
            target = versionRepository.save(LeavePlanVersion.builder()
                    .planId(planId).version(number).effectiveFrom(from)
                    .effectiveTo(nextStart == null ? null : nextStart.minusDays(1))
                    .createdBy(actor.getId()).build());
        }
        target.setCreatedBy(actor.getId());
        versionRepository.save(target);
        writeRules(target.getId(), req.rules(), types);
        planRuleService.invalidate();

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("plan", plan.getName());
        before.put("rules", beforeRules);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("plan", plan.getName());
        after.put("version", target.getVersion());
        after.put("effectiveFrom", from.toString());
        after.put("rules", newRules);
        after.put("recreditCredited", req.recreditCredited());
        int changedBalances = 0;
        if (req.recreditCredited()) {
            changedBalances = accrualService.reconcile(null, from, from.equals(LeaveBalanceService.yearStart(from.getYear())));
        }
        after.put("balancesUpdated", changedBalances);
        auditService.log(actor.getId(), "LEAVE_PLAN_VERSION_SAVED", plan.getId(), auditSnapshot.toJson(before), auditSnapshot.toJson(after));
        return detail(planId);
    }

    // ── Rules ───────────────────────────────────────────────────────────────────────────────────

    /** Checks the rules and returns their leave types by id. */
    Map<UUID, LeaveType> validateRules(List<TypeRuleRequest> rules) {
        Map<UUID, LeaveType> types = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (TypeRuleRequest r : rules) {
            if (!seen.add(r.leaveTypeId())) {
                throw new IllegalArgumentException("A leave type can be in a plan only once.");
            }
            LeaveType type = leaveTypeRepository.findById(r.leaveTypeId())
                    .orElseThrow(() -> new NoSuchElementException("Leave type not found: " + r.leaveTypeId()));
            types.put(type.getId(), type);
            RuleLimits l = limitsOf(r);
            String mode = modeOf(type, l);
            if (!LeavePlanTypeRule.MODE_QUOTA.equals(mode) && !type.isAnnualBalance() && !type.isCompOff()) {
                throw new IllegalArgumentException(type.getName() + ": only regular paid leave can be unlimited or earned-only.");
            }
            if (type.isPaid() && r.days() == null && LeavePlanTypeRule.MODE_QUOTA.equals(mode)) {
                throw new IllegalArgumentException(type.getName() + ": days are required for a paid leave type.");
            }
            if (r.carryForwardEnabled() && LeavePlanTypeRule.MODE_UNLIMITED.equals(mode)) {
                throw new IllegalArgumentException(type.getName() + ": unlimited leave has no balance to carry forward.");
            }
            validateLimits(type, l);
            if (r.carryForwardEnabled() && !type.isAnnualBalance()) {
                throw new IllegalArgumentException(type.getName() + ": carry-forward is only available for regular paid leave "
                        + "(not unpaid or per-occurrence leave such as Maternity).");
            }
            EligibilityConfig e = r.eligibility();
            if (e != null) {
                for (String id : nullSafe(e.departmentIds())) parseUuid(id, "department");
                for (String id : nullSafe(e.locationIds())) parseUuid(id, "location");
            }
        }
        return types;
    }

    private void writeRules(UUID versionId, List<TypeRuleRequest> rules, Map<UUID, LeaveType> types) {
        for (TypeRuleRequest req : rules) {
            LeaveType type = types.get(req.leaveTypeId());
            boolean annual = type.isAnnualBalance();
            RuleLimits l = limitsOf(req);
            String mode = modeOf(type, l);
            boolean quota = LeavePlanTypeRule.MODE_QUOTA.equals(mode);
            LeavePlanTypeRule rule = ruleRepository.save(LeavePlanTypeRule.builder()
                    .versionId(versionId)
                    .leaveTypeId(type.getId())
                    .days(!type.isPaid() ? null : quota ? req.days() : BigDecimal.ZERO)
                    .entitlementMode(mode)
                    .minDaysPerRequest(l.minDaysPerRequest())
                    .maxDaysPerRequest(l.maxDaysPerRequest())
                    .maxRequestsPerMonth(l.maxRequestsPerMonth())
                    .maxRequestsPerYear(l.maxRequestsPerYear())
                    .maxConsecutiveDays(l.maxConsecutiveDays())
                    .noticeDays(l.noticeDays())
                    .noticeAppliesOverDays(l.noticeDays() == null ? null : l.noticeAppliesOverDays())
                    .allowHalfDay(l.allowHalfDay() == null || l.allowHalfDay())
                    .allowHourly(l.allowHourly())
                    .minHours(l.allowHourly() ? l.minHours() : null)
                    .attachmentRequiredOverDays(l.attachmentRequiredOverDays())
                    .attachmentAllowedTypes(normalizedExtensions(l.attachmentAllowedTypes()))
                    .attachmentMaxMb(l.attachmentMaxMb())
                    .sandwichEnabled(sandwichOn(type, l))
                    .sandwichAppliesTo(sandwichAppliesTo(l))
                    .sandwichMinDays(sandwichOn(type, l) ? l.sandwichMinDays() : null)
                    .sandwichAcrossRequests(sandwichOn(type, l) && l.sandwichAcrossRequests())
                    .creditFrequency(annual && req.creditFrequency() != null ? req.creditFrequency() : LeaveEntitlementPolicy.YEARLY)
                    .creditTiming(accrualOf(type, req).timing())
                    .accrualPauseLopOverDays(accrualOf(type, req).pauseLopOverDays())
                    .accrualPauseUnpaidOverDays(accrualOf(type, req).pauseUnpaidOverDays())
                    .carryForwardEnabled(annual && req.carryForwardEnabled())
                    .carryForwardMaxDays(annual && req.carryForwardEnabled() ? req.carryForwardMaxDays() : null)
                    .carryForwardExpiryMonths(annual && req.carryForwardEnabled() ? req.carryForwardExpiryMonths() : null)
                    .carryForwardPriority(req.carryForwardPriority() != null ? req.carryForwardPriority() : LeaveType.CF_FIRST)
                    .minTenureMonths(LeaveEligibilityService.clean(req.eligibility()).minTenureMonths())
                    .allowedDuringProbation(LeaveEligibilityService.clean(req.eligibility()).allowedDuringProbation())
                    .build());
            EligibilityConfig c = LeaveEligibilityService.clean(req.eligibility());
            List<LeavePlanTypeEligibility> values = new ArrayList<>();
            c.genders().forEach(v -> values.add(eligibility(rule.getId(), LeavePlanTypeEligibility.GENDER, v)));
            c.departmentIds().forEach(v -> values.add(eligibility(rule.getId(), LeavePlanTypeEligibility.DEPARTMENT, v)));
            c.locationIds().forEach(v -> values.add(eligibility(rule.getId(), LeavePlanTypeEligibility.LOCATION, v)));
            c.employmentTypes().forEach(v -> values.add(eligibility(rule.getId(), LeavePlanTypeEligibility.EMPLOYMENT_TYPE, v)));
            c.maritalStatuses().forEach(v -> values.add(eligibility(rule.getId(), LeavePlanTypeEligibility.MARITAL_STATUS, v)));
            c.grades().forEach(v -> values.add(eligibility(rule.getId(), LeavePlanTypeEligibility.GRADE, v)));
            eligibilityRepository.saveAll(values);
            List<LeavePlanTypeOverride> overrides = new ArrayList<>();
            c.includeEmployeeIds().forEach(id -> overrides.add(LeavePlanTypeOverride.builder()
                    .ruleId(rule.getId()).employeeUserId(id).mode(LeavePlanTypeOverride.INCLUDE).build()));
            c.excludeEmployeeIds().forEach(id -> overrides.add(LeavePlanTypeOverride.builder()
                    .ruleId(rule.getId()).employeeUserId(id).mode(LeavePlanTypeOverride.EXCLUDE).build()));
            overrideRepository.saveAll(overrides);
            clubbingRepository.saveAll(nullSafe(l.notNextToTypeIds()).stream().distinct()
                    .map(id -> LeavePlanTypeClubbing.builder().ruleId(rule.getId()).blockedLeaveTypeId(id).build()).toList());
        }
    }

    /** The entitlement mode to store: Comp-Off is always earned-only (its days come from the comp-off ledger). */
    static String modeOf(LeaveType type, RuleLimits l) {
        if (type.isCompOff()) return LeavePlanTypeRule.MODE_EARNED_ONLY;
        return l.entitlementMode() == null ? LeavePlanTypeRule.MODE_QUOTA : l.entitlementMode();
    }

    private static RuleLimits limitsOf(TypeRuleRequest r) {
        return r.limits() == null ? RuleLimits.DEFAULTS : r.limits();
    }

    /** LV-02: the limits must make sense together. */
    private void validateLimits(LeaveType type, RuleLimits l) {
        String n = type.getName() + ": ";
        if (l.minDaysPerRequest() != null && l.maxDaysPerRequest() != null && l.minDaysPerRequest().compareTo(l.maxDaysPerRequest()) > 0) {
            throw new IllegalArgumentException(n + "the minimum days per request can't be more than the maximum.");
        }
        if (l.maxDaysPerRequest() != null && l.maxConsecutiveDays() != null && l.maxConsecutiveDays().compareTo(l.maxDaysPerRequest()) < 0) {
            throw new IllegalArgumentException(n + "the maximum days in a row can't be less than the maximum days per request.");
        }
        if (l.maxRequestsPerMonth() != null && l.maxRequestsPerYear() != null && l.maxRequestsPerMonth() > l.maxRequestsPerYear()) {
            throw new IllegalArgumentException(n + "the requests allowed a month can't be more than a year.");
        }
        if (l.minHours() != null && !l.allowHourly()) {
            throw new IllegalArgumentException(n + "minimum hours only applies when leave by the hour is allowed.");
        }
        if (l.noticeAppliesOverDays() != null && l.noticeDays() == null) {
            throw new IllegalArgumentException(n + "set the notice days for \"notice applies over\" to mean anything.");
        }
        String ext = l.attachmentAllowedTypes();
        if (ext != null && !ext.isBlank()) {
            for (String e : ext.split(",")) {
                String x = e.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
                if (!x.isEmpty() && !x.matches("[a-z0-9]{1,10}")) {
                    throw new IllegalArgumentException(n + "\"" + e.trim() + "\" isn't a file extension (e.g. pdf, jpg).");
                }
            }
        }
        for (UUID id : nullSafe(l.notNextToTypeIds())) {
            if (id.equals(type.getId())) {
                throw new IllegalArgumentException(n + "a leave type can't block itself; use the maximum days in a row.");
            }
            if (leaveTypeRepository.findById(id).isEmpty()) {
                throw new NoSuchElementException("Leave type not found: " + id);
            }
        }
    }

    /** LV-07: per-occurrence leave (Maternity) counts calendar days, so a sandwich rule means nothing for it. */
    private static boolean sandwichOn(LeaveType type, RuleLimits l) {
        return l.sandwichEnabled() && !type.isPerOccurrence();
    }

    private static String sandwichAppliesTo(RuleLimits l) {
        return l.sandwichAppliesTo() == null ? LeavePlanTypeRule.SANDWICH_BOTH : l.sandwichAppliesTo();
    }

    /** "PDF, .jpg " -> "pdf,jpg"; null when blank. */
    static String normalizedExtensions(String csv) {
        if (csv == null) return null;
        String out = java.util.Arrays.stream(csv.split(",")).map(e -> e.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""))
                .filter(e -> !e.isEmpty()).distinct().collect(java.util.stream.Collectors.joining(","));
        return out.isEmpty() ? null : out;
    }

    /** The stored rule's limits in request/response form. */
    private RuleLimits limitsDto(LeavePlanTypeRule r) {
        return new RuleLimits(r.getEntitlementMode(), r.getMinDaysPerRequest(), r.getMaxDaysPerRequest(),
                r.getMaxRequestsPerMonth(), r.getMaxRequestsPerYear(), r.getMaxConsecutiveDays(), r.getNoticeDays(),
                r.getNoticeAppliesOverDays(), r.isAllowHalfDay(), r.isAllowHourly(), r.getMinHours(),
                r.getAttachmentRequiredOverDays(), r.getAttachmentAllowedTypes(), r.getAttachmentMaxMb(),
                planRuleService.clubbingOf(r.getId()), r.isSandwichEnabled(),
                r.getSandwichAppliesTo() == null ? LeavePlanTypeRule.SANDWICH_BOTH : r.getSandwichAppliesTo(),
                r.getSandwichMinDays(), r.isSandwichAcrossRequests());
    }

    private static LeavePlanTypeEligibility eligibility(UUID ruleId, String type, String value) {
        return LeavePlanTypeEligibility.builder().ruleId(ruleId).ruleType(type).value(value).build();
    }

    /** A version's rules in request form, for comparing a save with what is already there. */
    private List<TypeRuleRequest> requestsOf(UUID versionId) {
        return ruleRepository.findByVersionId(versionId).stream().map(r -> new TypeRuleRequest(r.getLeaveTypeId(), r.getDays(),
                r.getCreditFrequency(), r.isCarryForwardEnabled(), r.getCarryForwardMaxDays(), r.getCarryForwardExpiryMonths(),
                r.getCarryForwardPriority(), planRuleService.eligibilityConfig(r.getId()), limitsDto(r), accrualDto(r))).toList();
    }

    /** Canonical JSON of a rule set (sorted, normalised), used both for "unchanged?" and for the audit trail. */
    private String rulesSnapshotOf(List<TypeRuleRequest> rules) {
        Map<UUID, LeaveType> types = new HashMap<>();
        for (TypeRuleRequest r : rules) leaveTypeRepository.findById(r.leaveTypeId()).ifPresent(t -> types.put(t.getId(), t));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TypeRuleRequest r : rules) {
            LeaveType t = types.get(r.leaveTypeId());
            if (t == null) continue;
            boolean annual = t.isAnnualBalance();
            boolean cf = annual && r.carryForwardEnabled();
            EligibilityConfig e = LeaveEligibilityService.clean(r.eligibility());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("leaveTypeCode", t.getCode());
            m.put("days", t.isPaid() && r.days() != null ? r.days().stripTrailingZeros().toPlainString() : null);
            m.put("creditFrequency", annual && r.creditFrequency() != null ? r.creditFrequency() : LeaveEntitlementPolicy.YEARLY);
            m.put("carryForwardEnabled", cf);
            m.put("carryForwardMaxDays", cf && r.carryForwardMaxDays() != null ? r.carryForwardMaxDays().stripTrailingZeros().toPlainString() : null);
            m.put("carryForwardExpiryMonths", cf ? r.carryForwardExpiryMonths() : null);
            m.put("carryForwardPriority", r.carryForwardPriority() != null ? r.carryForwardPriority() : LeaveType.CF_FIRST);
            m.put("genders", e.genders().stream().map(LeaveEligibilityService::norm).sorted().toList());
            m.put("departmentIds", e.departmentIds().stream().sorted().toList());
            m.put("locationIds", e.locationIds().stream().sorted().toList());
            m.put("employmentTypes", e.employmentTypes().stream().map(LeaveEligibilityService::norm).sorted().toList());
            m.put("includeEmployeeIds", e.includeEmployeeIds().stream().map(UUID::toString).sorted().toList());
            m.put("excludeEmployeeIds", e.excludeEmployeeIds().stream().map(UUID::toString).sorted().toList());
            m.put("maritalStatuses", e.maritalStatuses().stream().map(LeaveEligibilityService::norm).sorted().toList());
            m.put("grades", e.grades().stream().map(LeaveEligibilityService::norm).sorted().toList());
            m.put("minTenureMonths", e.minTenureMonths());
            m.put("allowedDuringProbation", e.allowedDuringProbation());
            RuleLimits l = limitsOf(r);
            String mode = modeOf(t, l);
            if (!LeavePlanTypeRule.MODE_QUOTA.equals(mode) && t.isPaid()) m.put("days", null);
            m.put("entitlementMode", mode);
            m.put("minDaysPerRequest", plain(l.minDaysPerRequest()));
            m.put("maxDaysPerRequest", plain(l.maxDaysPerRequest()));
            m.put("maxRequestsPerMonth", l.maxRequestsPerMonth());
            m.put("maxRequestsPerYear", l.maxRequestsPerYear());
            m.put("maxConsecutiveDays", plain(l.maxConsecutiveDays()));
            m.put("noticeDays", l.noticeDays());
            m.put("noticeAppliesOverDays", l.noticeDays() == null ? null : plain(l.noticeAppliesOverDays()));
            m.put("allowHalfDay", l.allowHalfDay() == null || l.allowHalfDay());
            m.put("allowHourly", l.allowHourly());
            m.put("minHours", l.allowHourly() ? plain(l.minHours()) : null);
            m.put("attachmentRequiredOverDays", plain(l.attachmentRequiredOverDays()));
            m.put("attachmentAllowedTypes", normalizedExtensions(l.attachmentAllowedTypes()));
            m.put("attachmentMaxMb", l.attachmentMaxMb());
            m.put("notNextTo", nullSafe(l.notNextToTypeIds()).stream().map(UUID::toString).distinct().sorted().toList());
            boolean sandwich = sandwichOn(t, l);
            m.put("sandwichEnabled", sandwich);
            m.put("sandwichAppliesTo", sandwich ? sandwichAppliesTo(l) : null);
            m.put("sandwichMinDays", sandwich ? plain(l.sandwichMinDays()) : null);
            m.put("sandwichAcrossRequests", sandwich && l.sandwichAcrossRequests());
            AccrualSettings a = accrualOf(t, r);
            m.put("creditTiming", a.timing());
            m.put("accrualPauseLopOverDays", plain(a.pauseLopOverDays()));
            m.put("accrualPauseUnpaidOverDays", plain(a.pauseUnpaidOverDays()));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> (String) m.get("leaveTypeCode")));
        return auditSnapshot.toJson(Map.of("types", out));
    }

    private static String plain(BigDecimal d) {
        return d == null ? null : d.stripTrailingZeros().toPlainString();
    }

    private String rulesSnapshot(UUID versionId) {
        return rulesSnapshotOf(requestsOf(versionId));
    }

    private TypeRuleDto toDto(LeavePlanTypeRule r, LeaveType t) {
        return new TypeRuleDto(t.getId(), t.getCode(), t.getName(), t.getClassification(),
                t.isPaid() ? t.getEntitlementBasis() : "UNLIMITED", t.isActive(), r.getDays(),
                t.isAnnualBalance() ? r.getCreditFrequency() : null, r.isCarryForwardEnabled(), r.getCarryForwardMaxDays(),
                r.getCarryForwardExpiryMonths(), r.getCarryForwardPriority(), planRuleService.eligibilityConfig(r.getId()),
                limitsDto(r), t.isAnnualBalance() ? accrualDto(r) : null);
    }

    static AccrualSettings accrualDto(LeavePlanTypeRule r) {
        return new AccrualSettings(r.getCreditTiming() == null ? LeavePlanTypeRule.TIMING_START : r.getCreditTiming(),
                r.getAccrualPauseLopOverDays(), r.getAccrualPauseUnpaidOverDays());
    }

    /** LV-03: the accrual settings to store — only regular paid (annual) types are credited, so only they have any. */
    static AccrualSettings accrualOf(LeaveType type, TypeRuleRequest r) {
        if (!type.isAnnualBalance() || r.accrual() == null) return AccrualSettings.DEFAULTS;
        AccrualSettings a = r.accrual();
        return new AccrualSettings(a.timing() == null ? LeavePlanTypeRule.TIMING_START : a.timing(),
                a.pauseLopOverDays(), a.pauseUnpaidOverDays());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private PlanSummary summary(LeavePlan p, LeavePlanCatalog catalog, LocalDate today, long employees) {
        Optional<LeavePlanVersion> current = catalog.versionAt(p.getId(), today);
        int typeCount = current.map(v -> catalog.rulesOfVersion(v.getId()).size()).orElse(0);
        return new PlanSummary(p.getId(), p.getName(), p.getDescription(), p.getLeaveYearStartMonth(), p.getStatus(),
                p.isOrgDefault(), current.map(LeavePlanVersion::getVersion).orElse(null),
                current.map(LeavePlanVersion::getEffectiveFrom).orElse(null), typeCount, employees, p.getUpdatedAt(),
                prorationDto(p));
    }

    private static com.nforce.onehr.dto.LeavePlanDtos.Proration prorationDto(LeavePlan p) {
        LeaveAccrualService.Proration r = LeaveAccrualService.Proration.of(p);
        return new com.nforce.onehr.dto.LeavePlanDtos.Proration(r.method(), r.cutoffDay(), r.rounding());
    }

    /** LV-04: null leaves the plan's proration as it is; a cut-off day only means something for MONTHS. */
    private static void applyProration(LeavePlan plan, com.nforce.onehr.dto.LeavePlanDtos.Proration p) {
        if (p == null) return;
        String method = p.method() == null ? LeavePlan.PRORATE_MONTHS : p.method();
        plan.setProrationMethod(method);
        plan.setProrationCutoffDay(LeavePlan.PRORATE_MONTHS.equals(method) ? p.cutoffDay() : null);
        plan.setProrationRounding(p.rounding() == null ? LeavePlan.ROUND_NEAREST_HALF : p.rounding());
    }

    private static VersionSummary versionSummary(LeavePlanVersion v, LocalDate today, Map<UUID, String> names) {
        String status = v.getEffectiveFrom().isAfter(today) ? STATUS_SCHEDULED
                : v.getEffectiveTo() != null && v.getEffectiveTo().isBefore(today) ? STATUS_PAST : STATUS_CURRENT;
        return new VersionSummary(v.getId(), v.getVersion(), v.getEffectiveFrom(), v.getEffectiveTo(), status,
                v.getCreatedBy() == null ? "System default" : names.getOrDefault(v.getCreatedBy(), "Unknown"), v.getCreatedAt());
    }

    private Map<UUID, String> userNames(Set<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : employeeRepository.findNamesByUserIds(ids)) names.put((UUID) row[0], (String) row[1]);
        }
        return names;
    }

    private Map<String, Object> planSnapshot(LeavePlan p, String rules) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("leaveYearStartMonth", p.getLeaveYearStartMonth());
        m.put("status", p.getStatus());
        m.put("orgDefault", p.isOrgDefault());
        m.put("prorationMethod", p.getProrationMethod());
        m.put("prorationCutoffDay", p.getProrationCutoffDay());
        m.put("prorationRounding", p.getProrationRounding());
        if (rules != null) m.put("rules", rules);
        return m;
    }

    private static void requireJanuary(Integer month) {
        if (month != null && month != 1) {
            throw new IllegalArgumentException("The leave year runs January to December for now; other start months are not available yet.");
        }
    }

    private LeavePlan requirePlan(UUID id) {
        return planRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Leave plan not found"));
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static void parseUuid(String value, String what) {
        if (value == null || value.isBlank()) return;
        try {
            UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + what + " id: " + value);
        }
    }
}
