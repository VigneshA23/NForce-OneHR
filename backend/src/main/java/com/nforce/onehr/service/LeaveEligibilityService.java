package com.nforce.onehr.service;

import com.nforce.onehr.dto.LeaveConfigDtos.EligibilityConfig;
import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who a leave type applies to (V314; per plan since LV-01). A type is available to an employee only if
 * their leave plan on that date includes it ({@link LeavePlanCatalog}), and then by its "applies to"
 * rules: an employee is eligible unless hand-picked to be excluded;
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

    private final LeavePlanRuleService planRuleService;
    private final EmployeeRepository employeeRepository;
    private final AttendanceProperties attendanceProperties;

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

    /**
     * The type's rule in the employee's plan on {@code date}, if its "applies to" rules admit the
     * employee. {@code profile} may be null (no employee record): only no rules or an INCLUDE admit them.
     */
    public static Optional<LeavePlanCatalog.TypeRule> applicableRule(LeavePlanCatalog catalog, UUID employeeUserId,
                                                                     LeaveEmployeeProfile profile, UUID leaveTypeId, LocalDate date) {
        return catalog.ruleFor(employeeUserId, leaveTypeId, date)
                .filter(r -> r.eligibility().matches(employeeUserId, profile));
    }

    /** Eligible today: the type is in the employee's plan and its "applies to" rules admit them. */
    @Transactional(readOnly = true)
    public boolean isEligible(UUID employeeUserId, LeaveType type) {
        return isEligible(employeeUserId, type, today());
    }

    @Transactional(readOnly = true)
    public boolean isEligible(UUID employeeUserId, LeaveType type, LocalDate date) {
        LeavePlanCatalog catalog = planRuleService.catalog(List.of(employeeUserId));
        return applicableRule(catalog, employeeUserId, profileOf(employeeUserId), type.getId(), date).isPresent();
    }

    /** {@code types} restricted to those the employee is eligible for today. */
    @Transactional(readOnly = true)
    public List<LeaveType> eligibleTypes(UUID employeeUserId, Collection<LeaveType> types) {
        LeavePlanCatalog catalog = planRuleService.catalog(List.of(employeeUserId));
        LocalDate today = today();
        LeaveEmployeeProfile profile = profileOf(employeeUserId);
        List<LeaveType> out = new ArrayList<>();
        for (LeaveType t : types) {
            if (applicableRule(catalog, employeeUserId, profile, t.getId(), today).isPresent()) out.add(t);
        }
        return out;
    }

    private LeaveEmployeeProfile profileOf(UUID employeeUserId) {
        return employeeRepository.findLeaveProfile(employeeUserId).orElse(null);
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
    }

    // ── Configuration helpers (used by LeavePlanService) ───────────────────────────────────────

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

    static Rules toRules(EligibilityConfig c) {
        Set<String> genders = new HashSet<>(), types = new HashSet<>();
        c.genders().forEach(g -> genders.add(norm(g)));
        c.employmentTypes().forEach(t -> types.add(norm(t)));
        return new Rules(genders, new HashSet<>(c.departmentIds()), new HashSet<>(c.locationIds()), types,
                new HashSet<>(c.includeEmployeeIds()), new HashSet<>(c.excludeEmployeeIds()));
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
