package com.nforce.onehr.service;

import com.nforce.onehr.dto.LeaveConfigDtos.EligibilityConfig;
import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.LeaveTypeEligibilityRule;
import com.nforce.onehr.entity.LeaveTypeEmployeeOverride;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveTypeEligibilityRuleRepository;
import com.nforce.onehr.repository.LeaveTypeEmployeeOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Who a leave type applies to (V314). An employee is eligible unless hand-picked to be excluded;
 * otherwise eligible when hand-picked to be included, or when they satisfy every rule type that has at
 * least one value (values of one rule type are OR'ed). No rules = everyone. Gender and employment
 * type compare case-insensitively; an employee with no gender on file never matches a gender rule.
 *
 * <p>Ineligible employees don't see the type, can't apply for it, get no balance or credits for it,
 * and penalties can't debit it. Losing eligibility later keeps what they already have.
 */
@Service
@RequiredArgsConstructor
public class LeaveEligibilityService {

    private final LeaveTypeEligibilityRuleRepository ruleRepository;
    private final LeaveTypeEmployeeOverrideRepository overrideRepository;
    private final EmployeeRepository employeeRepository;

    /** One type's rules, loaded once and applied to many employees. */
    public record Rules(Set<String> genders, Set<String> departmentIds, Set<String> locationIds,
                        Set<String> employmentTypes, Set<UUID> include, Set<UUID> exclude) {

        public static final Rules EVERYONE = new Rules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

        public boolean unrestricted() {
            return genders.isEmpty() && departmentIds.isEmpty() && locationIds.isEmpty() && employmentTypes.isEmpty()
                    && exclude.isEmpty();
        }

        /** {@code profile} may be null (a user with no employee record): only no rules or an INCLUDE admit them. */
        public boolean matches(UUID userId, LeaveEmployeeProfile profile) {
            if (exclude.contains(userId)) return false;
            if (include.contains(userId)) return true;
            if (genders.isEmpty() && departmentIds.isEmpty() && locationIds.isEmpty() && employmentTypes.isEmpty()) return true;
            if (profile == null) return false;
            return matchesText(genders, profile.gender())
                    && (departmentIds.isEmpty() || (profile.departmentId() != null && departmentIds.contains(profile.departmentId().toString())))
                    && (locationIds.isEmpty() || (profile.locationId() != null && locationIds.contains(profile.locationId().toString())))
                    && matchesText(employmentTypes, profile.employmentType());
        }

        private static boolean matchesText(Set<String> allowed, String value) {
            if (allowed.isEmpty()) return true;
            return value != null && !value.isBlank() && allowed.contains(norm(value));
        }
    }

    @Transactional(readOnly = true)
    public Rules rulesFor(UUID leaveTypeId) {
        if (leaveTypeId == null) return Rules.EVERYONE;
        Set<String> genders = new HashSet<>(), departments = new HashSet<>(), locations = new HashSet<>(), types = new HashSet<>();
        for (LeaveTypeEligibilityRule r : ruleRepository.findByLeaveTypeId(leaveTypeId)) {
            switch (r.getRuleType()) {
                case LeaveTypeEligibilityRule.GENDER -> genders.add(norm(r.getValue()));
                case LeaveTypeEligibilityRule.DEPARTMENT -> departments.add(r.getValue());
                case LeaveTypeEligibilityRule.LOCATION -> locations.add(r.getValue());
                case LeaveTypeEligibilityRule.EMPLOYMENT_TYPE -> types.add(norm(r.getValue()));
                default -> { }
            }
        }
        Set<UUID> include = new HashSet<>(), exclude = new HashSet<>();
        for (LeaveTypeEmployeeOverride o : overrideRepository.findByLeaveTypeId(leaveTypeId)) {
            (LeaveTypeEmployeeOverride.INCLUDE.equals(o.getMode()) ? include : exclude).add(o.getEmployeeUserId());
        }
        return new Rules(genders, departments, locations, types, include, exclude);
    }

    @Transactional(readOnly = true)
    public boolean isEligible(UUID employeeUserId, LeaveType type) {
        Rules rules = rulesFor(type.getId());
        if (rules.unrestricted() && rules.include().isEmpty()) return true;
        return rules.matches(employeeUserId, employeeRepository.findLeaveProfile(employeeUserId).orElse(null));
    }

    /** {@code type} restricted to the employee's eligibility; types with no rules always pass. */
    @Transactional(readOnly = true)
    public List<LeaveType> eligibleTypes(UUID employeeUserId, Collection<LeaveType> types) {
        LeaveEmployeeProfile profile = null;
        boolean loaded = false;
        List<LeaveType> out = new ArrayList<>();
        for (LeaveType t : types) {
            Rules rules = rulesFor(t.getId());
            if (!(rules.unrestricted() && rules.include().isEmpty())) {
                if (!loaded) {
                    profile = employeeRepository.findLeaveProfile(employeeUserId).orElse(null);
                    loaded = true;
                }
                if (!rules.matches(employeeUserId, profile)) continue;
            }
            out.add(t);
        }
        return out;
    }

    // ── Configuration ───────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EligibilityConfig configOf(UUID leaveTypeId) {
        Rules r = rulesFor(leaveTypeId);
        List<String> genders = new ArrayList<>(), employmentTypes = new ArrayList<>();
        for (LeaveTypeEligibilityRule rule : ruleRepository.findByLeaveTypeId(leaveTypeId)) {
            // keep the stored spelling ("Female", "FULL_TIME") for display
            if (LeaveTypeEligibilityRule.GENDER.equals(rule.getRuleType())) genders.add(rule.getValue());
            if (LeaveTypeEligibilityRule.EMPLOYMENT_TYPE.equals(rule.getRuleType())) employmentTypes.add(rule.getValue());
        }
        return new EligibilityConfig(sorted(genders), sorted(r.departmentIds()), sorted(r.locationIds()), sorted(employmentTypes),
                new ArrayList<>(r.include()), new ArrayList<>(r.exclude()));
    }

    /** Replaces the type's rules and overrides with {@code config}. */
    @Transactional
    public void replace(UUID leaveTypeId, EligibilityConfig config) {
        EligibilityConfig c = clean(config);
        ruleRepository.deleteByLeaveTypeId(leaveTypeId);
        overrideRepository.deleteByLeaveTypeId(leaveTypeId);
        List<LeaveTypeEligibilityRule> rules = new ArrayList<>();
        c.genders().forEach(v -> rules.add(rule(leaveTypeId, LeaveTypeEligibilityRule.GENDER, v)));
        c.departmentIds().forEach(v -> rules.add(rule(leaveTypeId, LeaveTypeEligibilityRule.DEPARTMENT, v)));
        c.locationIds().forEach(v -> rules.add(rule(leaveTypeId, LeaveTypeEligibilityRule.LOCATION, v)));
        c.employmentTypes().forEach(v -> rules.add(rule(leaveTypeId, LeaveTypeEligibilityRule.EMPLOYMENT_TYPE, v)));
        ruleRepository.saveAll(rules);
        List<LeaveTypeEmployeeOverride> overrides = new ArrayList<>();
        c.includeEmployeeIds().forEach(id -> overrides.add(LeaveTypeEmployeeOverride.builder()
                .leaveTypeId(leaveTypeId).employeeUserId(id).mode(LeaveTypeEmployeeOverride.INCLUDE).build()));
        c.excludeEmployeeIds().forEach(id -> overrides.add(LeaveTypeEmployeeOverride.builder()
                .leaveTypeId(leaveTypeId).employeeUserId(id).mode(LeaveTypeEmployeeOverride.EXCLUDE).build()));
        overrideRepository.saveAll(overrides);
    }

    /** How many current employees {@code config} would cover, out of all current employees. */
    @Transactional(readOnly = true)
    public int[] preview(EligibilityConfig config) {
        Rules rules = toRules(clean(config));
        int eligible = 0, total = 0;
        for (LeaveEmployeeProfile p : employeeRepository.findLeaveProfiles()) {
            if (!p.employed()) continue;
            total++;
            if (rules.matches(p.userId(), p)) eligible++;
        }
        return new int[] {eligible, total};
    }

    /** Trimmed, de-duplicated, nulls dropped; an employee both included and excluded is excluded. */
    static EligibilityConfig clean(EligibilityConfig c) {
        if (c == null) return new EligibilityConfig(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        Set<UUID> exclude = new LinkedHashSet<>(nonNull(c.excludeEmployeeIds()));
        Set<UUID> include = new LinkedHashSet<>(nonNull(c.includeEmployeeIds()));
        include.removeAll(exclude);
        return new EligibilityConfig(texts(c.genders()), texts(c.departmentIds()), texts(c.locationIds()), texts(c.employmentTypes()),
                new ArrayList<>(include), new ArrayList<>(exclude));
    }

    private static Rules toRules(EligibilityConfig c) {
        Set<String> genders = new HashSet<>(), types = new HashSet<>();
        c.genders().forEach(g -> genders.add(norm(g)));
        c.employmentTypes().forEach(t -> types.add(norm(t)));
        return new Rules(genders, new HashSet<>(c.departmentIds()), new HashSet<>(c.locationIds()), types,
                new HashSet<>(c.includeEmployeeIds()), new HashSet<>(c.excludeEmployeeIds()));
    }

    private static LeaveTypeEligibilityRule rule(UUID typeId, String ruleType, String value) {
        return LeaveTypeEligibilityRule.builder().leaveTypeId(typeId).ruleType(ruleType).value(value).build();
    }

    private static List<String> texts(List<String> values) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String v : nonNull(values)) {
            if (v == null || v.isBlank()) continue;
            String t = v.trim();
            if (t.length() > 100) throw new IllegalArgumentException("Eligibility values can be at most 100 characters");
            if (seen.add(norm(t))) out.add(t);
        }
        return out;
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).toList();
    }

    private static List<String> sorted(Collection<String> values) {
        return values.stream().sorted().toList();
    }

    static String norm(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
