package com.nforce.onehr.service.assignment;

import com.nforce.onehr.entity.AssignmentRule;
import com.nforce.onehr.entity.AssignmentRuleAssignment;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AssignmentRuleAssignmentRepository;
import com.nforce.onehr.repository.AssignmentRuleRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.EmployeeShiftAssignmentResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PN-04 - THE shared assignment-rule engine (also used by leave plans LV-01 and WFH policies
 * WFH-02, which only supply an {@link AssignmentTargetResolver}).
 *
 * <p>Matching is pure: a rule matches when every criterion it sets (department, location, shift,
 * employment type, grade) equals the employee's; among matching active rules whose target is
 * usable, the lowest priority number wins. The outcome is RECORDED, effective-dated, in
 * {@code assignment_rule_assignments}: {@link #reevaluate} is called whenever an employee's
 * attributes change or the rules change, closes the previous record and opens a new one only when
 * the outcome actually changes, and resolution for any date reads those records - so history is
 * never rewritten. Precedence against an explicit per-employee allocation is each domain
 * resolver's job (the allocation always wins); this engine only answers "what do the rules say".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentRuleEngine {

    private final AssignmentRuleRepository ruleRepository;
    private final AssignmentRuleAssignmentRepository assignmentRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeShiftAssignmentResolver shiftResolver;
    private final List<AssignmentTargetResolver> targetResolvers;

    // -- pure matching -------------------------------------------------------------------------

    public static boolean matches(AssignmentRule r, EmployeeFacts f) {
        if (r.getDepartmentId() != null && !r.getDepartmentId().equals(f.departmentId())) return false;
        if (r.getLocationId() != null && !r.getLocationId().equals(f.locationId())) return false;
        if (r.getShiftId() != null && !r.getShiftId().equals(f.shiftId())) return false;
        if (r.getEmploymentType() != null && !r.getEmploymentType().equalsIgnoreCase(f.employmentType())) return false;
        if (r.getGrade() != null && (f.grade() == null || !r.getGrade().trim().equalsIgnoreCase(f.grade().trim()))) return false;
        return true;
    }

    /** First match in {@code rulesInPriorityOrder} (already sorted, lowest number first). */
    public static Optional<AssignmentRule> firstMatch(List<AssignmentRule> rulesInPriorityOrder, EmployeeFacts f) {
        return rulesInPriorityOrder.stream().filter(r -> matches(r, f)).findFirst();
    }

    // -- facts ---------------------------------------------------------------------------------

    public EmployeeFacts factsOf(Employee e, LocalDate date) {
        UUID shiftId = null;
        try {
            // The effective-dated assignment is authoritative; Employee.shift is only a display cache.
            shiftId = shiftResolver.resolveIfPresent(e.getUserId(), date)
                    .map(a -> a.getShift() != null ? a.getShift().getId() : null).orElse(null);
        } catch (RuntimeException ex) {
            log.debug("AssignmentRuleEngine: shift lookup failed for {}: {}", e.getUserId(), ex.getMessage());
        }
        if (shiftId == null && e.getShift() != null) shiftId = e.getShift().getId();
        return new EmployeeFacts(
                e.getUserId(),
                e.getDepartment() != null ? e.getDepartment().getId() : null,
                e.getLocation() != null ? e.getLocation().getId() : null,
                shiftId,
                e.getEmploymentType(),
                e.getDesignation() != null ? e.getDesignation().getGrade() : null);
    }

    // -- re-evaluation (records the outcome, effective-dated) ----------------------------------

    /** Re-evaluates one employee for one domain as of {@code today}; true when the recorded outcome changed. */
    @Transactional
    public boolean reevaluate(String domain, Employee employee, LocalDate today) {
        return reevaluate(domain, employee, today, usableRules(domain));
    }

    private boolean reevaluate(String domain, Employee employee, LocalDate today, List<AssignmentRule> rules) {
        Optional<AssignmentRule> match = firstMatch(rules, factsOf(employee, today));
        Optional<AssignmentRuleAssignment> open = assignmentRepository
                .findFirstByDomainAndEmployeeUserIdAndEffectiveToIsNullOrderByEffectiveFromDesc(domain, employee.getUserId());
        if (match.isEmpty()) {
            if (open.isEmpty()) return false;
            close(open.get(), today);
            return true;
        }
        AssignmentRule rule = match.get();
        if (open.isPresent() && open.get().getTargetId().equals(rule.getTargetId())
                && Objects.equals(open.get().getRuleId(), rule.getId())) {
            return false;
        }
        open.ifPresent(o -> close(o, today));
        assignmentRepository.save(AssignmentRuleAssignment.builder()
                .domain(domain).employeeUserId(employee.getUserId()).targetId(rule.getTargetId())
                .ruleId(rule.getId()).ruleName(rule.getName()).effectiveFrom(today).build());
        return true;
    }

    /** Re-evaluates every non-deleted employee for one domain; returns how many outcomes changed. */
    @Transactional
    public int reevaluateAll(String domain, LocalDate today) {
        List<AssignmentRule> rules = usableRules(domain);
        int changed = 0;
        for (Employee e : employeeRepository.findAll()) {
            if (e.getUser() != null && e.getUser().getDeletedAt() != null) continue;
            if (reevaluate(domain, e, today, rules)) changed++;
        }
        return changed;
    }

    /** One employee, every domain - the hook for "employee attributes changed". */
    @Transactional
    public void reevaluateAllDomains(Employee employee, LocalDate today) {
        for (String domain : AssignmentDomain.ALL) {
            reevaluate(domain, employee, today);
        }
    }

    /** A row that began today (or later) is dropped rather than closed to an inverted range. */
    private void close(AssignmentRuleAssignment row, LocalDate today) {
        if (!row.getEffectiveFrom().isBefore(today)) {
            assignmentRepository.delete(row);
        } else {
            row.setEffectiveTo(today.minusDays(1));
            assignmentRepository.save(row);
        }
    }

    private List<AssignmentRule> usableRules(String domain) {
        Optional<AssignmentTargetResolver> resolver = targetResolver(domain);
        return ruleRepository.findByDomainAndActiveTrueOrderByPriorityAscCreatedAtAsc(domain).stream()
                // A rule pointing at a deleted/inactive policy is skipped, not matched.
                .filter(r -> resolver.map(t -> t.isUsable(r.getTargetId())).orElse(false))
                .toList();
    }

    // -- resolution ----------------------------------------------------------------------------

    /** What the rules assigned to this employee on {@code date}, if anything. */
    @Transactional(readOnly = true)
    public Optional<AssignmentRuleAssignment> resolveAt(String domain, UUID employeeUserId, LocalDate date) {
        return assignmentRepository.findEffectiveAt(domain, employeeUserId, date).stream().findFirst();
    }

    /** Every employee's rule outcome on {@code date}, keyed by employee - one query, for bulk screens. */
    @Transactional(readOnly = true)
    public Map<UUID, AssignmentRuleAssignment> allEffectiveAt(String domain, LocalDate date) {
        Map<UUID, AssignmentRuleAssignment> out = new HashMap<>();
        for (AssignmentRuleAssignment a : assignmentRepository.findAllEffectiveAt(domain, date)) {
            out.merge(a.getEmployeeUserId(), a, (x, y) -> x.getEffectiveFrom().isAfter(y.getEffectiveFrom()) ? x : y);
        }
        return out;
    }

    public Optional<AssignmentTargetResolver> targetResolver(String domain) {
        return targetResolvers.stream().filter(t -> t.domain().equals(domain)).findFirst();
    }
}
