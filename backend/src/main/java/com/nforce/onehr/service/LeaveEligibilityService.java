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
 * least one value (values of one rule type are OR'ed). No rules = everyone. Gender, marital status, grade
 * and employment type compare case-insensitively; an employee with no value on file never matches such a rule.
 *
 * <p>Ineligible employees don't see the type, can't apply for it, get no balance or credits for it,
 * and penalties can't debit it. Losing eligibility later keeps what they already have.
 *
 * <p>LV-06 adds two conditions about <em>when</em>, checked on the leave's start date: a minimum tenure
 * (months since joining) and "not during probation". The type stays listed for the employee with the date it
 * becomes available; requests starting before then are refused with that date. Credits of a type not allowed
 * during probation are held until probation is over, then caught up (see {@link LeaveAccrualService}).
 */
@Service
@RequiredArgsConstructor
public class LeaveEligibilityService {

    private final LeavePlanRuleService planRuleService;
    private final EmployeeRepository employeeRepository;
    private final AttendanceProperties attendanceProperties;

    /** One type's rules, loaded once and applied to many employees. */
    public record Rules(Set<String> genders, Set<String> departmentIds, Set<String> locationIds,
                        Set<String> employmentTypes, Set<UUID> include, Set<UUID> exclude,
                        Set<String> maritalStatuses, Set<String> grades, Integer minTenureMonths, boolean allowedDuringProbation) {

        public static final Rules EVERYONE = new Rules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

        /** Without the LV-06 criteria. */
        public Rules(Set<String> genders, Set<String> departmentIds, Set<String> locationIds,
                     Set<String> employmentTypes, Set<UUID> include, Set<UUID> exclude) {
            this(genders, departmentIds, locationIds, employmentTypes, include, exclude, Set.of(), Set.of(), null, true);
        }

        public boolean unrestricted() {
            return noCriteria() && exclude.isEmpty() && minTenureMonths == null && allowedDuringProbation;
        }

        private boolean noCriteria() {
            return genders.isEmpty() && departmentIds.isEmpty() && locationIds.isEmpty() && employmentTypes.isEmpty()
                    && maritalStatuses.isEmpty() && grades.isEmpty();
        }

        /**
         * Who the type is for (not when). {@code profile} may be null (a user with no employee record): only no
         * rules or an INCLUDE admit them.
         */
        public boolean matches(UUID userId, LeaveEmployeeProfile profile) {
            if (exclude.contains(userId)) return false;
            if (include.contains(userId)) return true;
            if (noCriteria()) return true;
            if (profile == null) return false;
            return matchesText(genders, profile.gender())
                    && (departmentIds.isEmpty() || (profile.departmentId() != null && departmentIds.contains(profile.departmentId().toString())))
                    && (locationIds.isEmpty() || (profile.locationId() != null && locationIds.contains(profile.locationId().toString())))
                    && matchesText(employmentTypes, profile.employmentType())
                    && matchesText(maritalStatuses, profile.maritalStatus())
                    && matchesText(grades, profile.grade());
        }

        /**
         * LV-06: why the type can't be taken on {@code date} yet (tenure or probation), or empty. A hand-picked
         * include is never held back.
         */
        public Optional<Gate> gate(UUID userId, LeaveEmployeeProfile profile, LocalDate date) {
            if (include.contains(userId)) return Optional.empty();
            if (minTenureMonths != null) {
                LocalDate from = profile == null || profile.joiningDate() == null ? null : profile.joiningDate().plusMonths(minTenureMonths);
                if (from == null || date.isBefore(from)) return Optional.of(new Gate(Gate.TENURE, from, minTenureMonths));
            }
            if (probationBlocks(userId, profile, date)) {
                return Optional.of(new Gate(Gate.PROBATION, profile.probationOver(), null));
            }
            return Optional.empty();
        }

        /** Tenure reached on {@code date} (credits of a month need the tenure on the month's date). */
        public boolean tenureMet(UUID userId, LeaveEmployeeProfile profile, LocalDate date) {
            return gate(userId, profile, date).filter(g -> Gate.TENURE.equals(g.kind())).isEmpty();
        }

        /** The type isn't allowed during probation and the employee is on probation on {@code date}. */
        public boolean probationBlocks(UUID userId, LeaveEmployeeProfile profile, LocalDate date) {
            return !allowedDuringProbation && !include.contains(userId) && profile != null && profile.onProbation(date);
        }

        private static boolean matchesText(Set<String> allowed, String value) {
            if (allowed.isEmpty()) return true;
            return value != null && !value.isBlank() && allowed.contains(norm(value));
        }
    }

    /**
     * LV-06: a dated condition not met yet. {@code kind} TENURE or PROBATION; {@code availableFrom} the first
     * day it is met (null when unknown: no joining date, or no probation end on file).
     */
    public record Gate(String kind, LocalDate availableFrom, Integer months) {
        public static final String TENURE = "TENURE";
        public static final String PROBATION = "PROBATION";
    }

    /**
     * Whether an employee can take a type for leave starting on a date. {@code forEmployee}: the type is in their
     * plan and its "who" rules admit them (otherwise it isn't shown at all). {@code reason}: why not, for the
     * employee; {@code availableFrom}: when a dated condition will be met (may be null).
     */
    public record Availability(boolean available, boolean forEmployee, String reason, LocalDate availableFrom) {}

    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    static Availability availability(LeavePlanCatalog catalog, UUID employeeUserId, LeaveEmployeeProfile profile,
                                     LeaveType type, LocalDate date) {
        Optional<LeavePlanCatalog.TypeRule> rule = applicableRule(catalog, employeeUserId, profile, type.getId(), date);
        if (rule.isEmpty()) return new Availability(false, false, type.getName() + " does not apply to you.", null);
        Optional<Gate> gate = rule.get().eligibility().gate(employeeUserId, profile, date);
        if (gate.isEmpty()) return new Availability(true, true, null, null);
        Gate g = gate.get();
        String from = g.availableFrom() == null ? "" : ": you can take it from " + g.availableFrom().format(DAY);
        String reason = Gate.TENURE.equals(g.kind())
                ? type.getName() + " needs " + g.months() + (g.months() == 1 ? " month" : " months") + " of service"
                        + (g.availableFrom() == null ? " (your joining date isn't on file)" : from) + "."
                : type.getName() + " isn't available during probation" + from + ".";
        return new Availability(false, true, reason, g.availableFrom());
    }

    /** Whether {@code type} can be taken for leave starting on {@code date}, and why not. */
    @Transactional(readOnly = true)
    public Availability availability(UUID employeeUserId, LeaveType type, LocalDate date) {
        return availability(planRuleService.catalog(List.of(employeeUserId)), employeeUserId, profileOf(employeeUserId), type, date);
    }

    /**
     * The types that are for the employee (their "who" rules, today), each with whether it can be taken today —
     * the apply form lists them all and shows when a held-back one becomes available.
     */
    @Transactional(readOnly = true)
    public java.util.Map<LeaveType, Availability> typesForEmployee(UUID employeeUserId, Collection<LeaveType> types) {
        LeavePlanCatalog catalog = planRuleService.catalog(List.of(employeeUserId));
        LeaveEmployeeProfile profile = profileOf(employeeUserId);
        LocalDate today = today();
        java.util.Map<LeaveType, Availability> out = new java.util.LinkedHashMap<>();
        for (LeaveType t : types) {
            Availability a = availability(catalog, employeeUserId, profile, t, today);
            if (a.forEmployee()) out.put(t, a);
        }
        return out;
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

    /**
     * The type is for this employee today: in their plan and admitted by its "who" rules. Tenure / probation
     * aren't considered (balances, opening balances and penalty debits still apply to a type held back by them).
     */
    @Transactional(readOnly = true)
    public boolean isEligible(UUID employeeUserId, LeaveType type) {
        return availability(employeeUserId, type, today()).forEmployee();
    }

    /** LV-06: eligible for leave starting on {@code date} — the "who" rules and the dated conditions (tenure, probation). */
    @Transactional(readOnly = true)
    public boolean isEligible(UUID employeeUserId, LeaveType type, LocalDate date) {
        return availability(employeeUserId, type, date).available();
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
            if (rules.matches(p.userId(), p) && rules.gate(p.userId(), p, today()).isEmpty()) eligible++;
        }
        return new int[] {eligible, total};
    }

    /** Trimmed, de-duplicated, nulls dropped; an employee both included and excluded is excluded. */
    static EligibilityConfig clean(EligibilityConfig c) {
        if (c == null) return new EligibilityConfig(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, true);
        Set<UUID> exclude = new LinkedHashSet<>(nonNull(c.excludeEmployeeIds()));
        Set<UUID> include = new LinkedHashSet<>(nonNull(c.includeEmployeeIds()));
        include.removeAll(exclude);
        return new EligibilityConfig(texts(c.genders()), texts(c.departmentIds()), texts(c.locationIds()), texts(c.employmentTypes()),
                new ArrayList<>(include), new ArrayList<>(exclude), texts(c.maritalStatuses()), texts(c.grades()),
                c.minTenureMonths(), c.allowedDuringProbation() == null || c.allowedDuringProbation());
    }

    static Rules toRules(EligibilityConfig c) {
        Set<String> genders = new HashSet<>(), types = new HashSet<>(), marital = new HashSet<>(), grades = new HashSet<>();
        c.genders().forEach(g -> genders.add(norm(g)));
        c.employmentTypes().forEach(t -> types.add(norm(t)));
        c.maritalStatuses().forEach(m -> marital.add(norm(m)));
        c.grades().forEach(g -> grades.add(norm(g)));
        return new Rules(genders, new HashSet<>(c.departmentIds()), new HashSet<>(c.locationIds()), types,
                new HashSet<>(c.includeEmployeeIds()), new HashSet<>(c.excludeEmployeeIds()), marital, grades,
                c.minTenureMonths(), c.allowedDuringProbation() == null || c.allowedDuringProbation());
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
