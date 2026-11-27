package com.nforce.onehr.service.assignment;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.assignment.AssignmentRuleDto;
import com.nforce.onehr.dto.assignment.ReevaluateResultResponse;
import com.nforce.onehr.dto.assignment.ReorderAssignmentRulesRequest;
import com.nforce.onehr.dto.assignment.UpsertAssignmentRuleRequest;
import com.nforce.onehr.entity.AssignmentRule;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.AssignmentRuleAssignmentRepository;
import com.nforce.onehr.repository.AssignmentRuleRepository;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LocationRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PN-04 - HR's management of assignment rules for any domain (criteria + priority -> target policy).
 * Every change re-evaluates the whole domain so the recorded, effective-dated assignments match the
 * new rules straight away (see {@link AssignmentRuleEngine}). Permission is the target domain's own
 * module (penalization:* for penalization policies), never a generic grant.
 */
@Service
@RequiredArgsConstructor
public class AssignmentRuleService {

    private final AssignmentRuleRepository ruleRepository;
    private final AssignmentRuleAssignmentRepository assignmentRepository;
    private final AssignmentRuleEngine engine;
    private final DepartmentRepository departmentRepository;
    private final LocationRepository locationRepository;
    private final ShiftRepository shiftRepository;
    private final EmployeeRepository employeeRepository;
    private final EffectivePermissionService effectivePermissionService;
    private final AuditService auditService;
    private final AttendanceProperties attendanceProperties;

    @Transactional(readOnly = true)
    public List<AssignmentRuleDto> list(String actorEmail, String domain) {
        requirePermission(actorEmail, domain, "VIEW");
        return toDtos(ruleRepository.findByDomainOrderByPriorityAscCreatedAtAsc(domain));
    }

    @Transactional
    public AssignmentRuleDto create(String actorEmail, UpsertAssignmentRuleRequest req) {
        Employee actor = requirePermission(actorEmail, req.getDomain(), "CREATE");
        AssignmentRule rule = AssignmentRule.builder().domain(req.getDomain()).createdBy(actor.getUserId()).build();
        apply(rule, req);
        if (rule.getPriority() == 0 && req.getPriority() == null) {
            rule.setPriority(ruleRepository.findByDomainOrderByPriorityAscCreatedAtAsc(req.getDomain()).stream()
                    .mapToInt(AssignmentRule::getPriority).max().orElse(0) + 10);
        }
        rule = ruleRepository.save(rule);
        auditService.log(actor.getUserId(), "ASSIGNMENT_RULE_CREATED", rule.getId(), null, auditJson(rule));
        reevaluate(rule.getDomain());
        return toDtos(List.of(rule)).get(0);
    }

    @Transactional
    public AssignmentRuleDto update(String actorEmail, UUID id, UpsertAssignmentRuleRequest req) {
        AssignmentRule rule = require(id);
        Employee actor = requirePermission(actorEmail, rule.getDomain(), "EDIT");
        if (!rule.getDomain().equals(req.getDomain())) {
            throw new IllegalArgumentException("A rule's domain cannot be changed");
        }
        apply(rule, req);
        rule = ruleRepository.save(rule);
        auditService.log(actor.getUserId(), "ASSIGNMENT_RULE_UPDATED", rule.getId(), null, auditJson(rule));
        reevaluate(rule.getDomain());
        return toDtos(List.of(rule)).get(0);
    }

    @Transactional
    public void delete(String actorEmail, UUID id) {
        AssignmentRule rule = require(id);
        Employee actor = requirePermission(actorEmail, rule.getDomain(), "DELETE");
        ruleRepository.delete(rule);
        ruleRepository.flush();
        auditService.log(actor.getUserId(), "ASSIGNMENT_RULE_DELETED", id, auditJson(rule), null);
        reevaluate(rule.getDomain());
    }

    /** Re-numbers priorities 10, 20, 30... in the given order, then re-evaluates. */
    @Transactional
    public List<AssignmentRuleDto> reorder(String actorEmail, ReorderAssignmentRulesRequest req) {
        Employee actor = requirePermission(actorEmail, req.getDomain(), "EDIT");
        List<AssignmentRule> all = ruleRepository.findByDomainOrderByPriorityAscCreatedAtAsc(req.getDomain());
        Map<UUID, AssignmentRule> byId = all.stream().collect(Collectors.toMap(AssignmentRule::getId, Function.identity()));
        if (req.getOrderedRuleIds().size() != byId.size() || !byId.keySet().containsAll(req.getOrderedRuleIds())) {
            throw new IllegalArgumentException("The order must list every rule of this domain exactly once");
        }
        int p = 10;
        for (UUID id : req.getOrderedRuleIds()) {
            byId.get(id).setPriority(p);
            p += 10;
        }
        ruleRepository.saveAll(byId.values());
        auditService.log(actor.getUserId(), "ASSIGNMENT_RULES_REORDERED", null, null, json(java.util.Map.of("domain", req.getDomain())));
        reevaluate(req.getDomain());
        return toDtos(ruleRepository.findByDomainOrderByPriorityAscCreatedAtAsc(req.getDomain()));
    }

    /** Admin "Re-evaluate all": repairs drift and reports how many employees' assignments changed. */
    @Transactional
    public ReevaluateResultResponse reevaluateAll(String actorEmail, String domain) {
        Employee actor = requirePermission(actorEmail, domain, "EDIT");
        int changed = reevaluate(domain);
        auditService.log(actor.getUserId(), "ASSIGNMENT_RULES_REEVALUATED", null, null, json(java.util.Map.of("domain", domain, "changed", changed)));
        return ReevaluateResultResponse.builder().domain(domain).changed(changed).build();
    }

    /** Priority + human-readable criteria of one rule, for the "why this policy" explanation. */
    public record RuleDescription(int priority, String criteriaText) {}

    @Transactional(readOnly = true)
    public java.util.Optional<RuleDescription> describe(UUID ruleId) {
        if (ruleId == null) return java.util.Optional.empty();
        return ruleRepository.findById(ruleId).map(r -> {
            Map<UUID, String> d = r.getDepartmentId() == null ? Map.of()
                    : departmentRepository.findById(r.getDepartmentId()).map(x -> Map.of(x.getId(), x.getName())).orElse(Map.of());
            Map<UUID, String> l = r.getLocationId() == null ? Map.of()
                    : locationRepository.findById(r.getLocationId()).map(x -> Map.of(x.getId(), x.getName())).orElse(Map.of());
            Map<UUID, String> s = r.getShiftId() == null ? Map.of()
                    : shiftRepository.findById(r.getShiftId()).map(x -> Map.of(x.getId(), x.getName())).orElse(Map.of());
            return new RuleDescription(r.getPriority(), criteriaText(r, d, l, s));
        });
    }

    // -- internals -----------------------------------------------------------------------------

    private int reevaluate(String domain) {
        return engine.reevaluateAll(domain, LocalDate.now(ZoneId.of(attendanceProperties.getZone())));
    }

    private void apply(AssignmentRule rule, UpsertAssignmentRuleRequest req) {
        AssignmentTargetResolver target = engine.targetResolver(req.getDomain())
                .orElseThrow(() -> new IllegalArgumentException("Auto-assignment is not available for " + req.getDomain() + " yet"));
        if (!target.isUsable(req.getTargetId())) {
            throw new IllegalArgumentException("The selected policy does not exist or is not active");
        }
        String employmentType = blankToNull(req.getEmploymentType());
        String grade = blankToNull(req.getGrade());
        if (req.getDepartmentId() == null && req.getLocationId() == null && req.getShiftId() == null
                && employmentType == null && grade == null) {
            throw new IllegalArgumentException("A rule needs at least one condition (department, location, shift, employment type or grade)");
        }
        if (req.getDepartmentId() != null && !departmentRepository.existsById(req.getDepartmentId()))
            throw new IllegalArgumentException("Department not found");
        if (req.getLocationId() != null && !locationRepository.existsById(req.getLocationId()))
            throw new IllegalArgumentException("Location not found");
        if (req.getShiftId() != null && !shiftRepository.existsById(req.getShiftId()))
            throw new IllegalArgumentException("Shift not found");
        rule.setTargetId(req.getTargetId());
        rule.setName(req.getName().trim());
        if (req.getPriority() != null) rule.setPriority(req.getPriority());
        rule.setDepartmentId(req.getDepartmentId());
        rule.setLocationId(req.getLocationId());
        rule.setShiftId(req.getShiftId());
        rule.setEmploymentType(employmentType);
        rule.setGrade(grade);
        if (req.getActive() != null) rule.setActive(req.getActive());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper AUDIT_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * audit_log.before_state/after_state are JSON columns: a bare "domain:name" string made every rule
     * create/update/delete fail with a 500 (invalid input syntax for type json).
     */
    static String json(java.util.Map<String, Object> fields) {
        try {
            return AUDIT_JSON.writeValueAsString(fields);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String auditJson(AssignmentRule r) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("domain", r.getDomain());
        m.put("name", r.getName());
        m.put("targetId", r.getTargetId() == null ? null : r.getTargetId().toString());
        m.put("priority", r.getPriority());
        m.put("active", r.isActive());
        return json(m);
    }

    private AssignmentRule require(UUID id) {
        return ruleRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Assignment rule not found"));
    }

    private Employee requirePermission(String actorEmail, String domain, String action) {
        AssignmentTargetResolver target = engine.targetResolver(domain)
                .orElseThrow(() -> new IllegalArgumentException("Auto-assignment is not available for " + domain + " yet"));
        Employee actor = employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException("No employee profile found for this account."));
        String authority = target.permissionModule() + ":" + target.permissionAction(action) + ":ALL";
        if (!effectivePermissionService.hasAuthority(actor.getUser(), authority)) {
            throw new AccessDeniedException("Missing " + authority);
        }
        return actor;
    }

    private List<AssignmentRuleDto> toDtos(List<AssignmentRule> rules) {
        Map<UUID, String> departments = departmentRepository.findAllById(
                rules.stream().map(AssignmentRule::getDepartmentId).filter(java.util.Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        Map<UUID, String> locations = locationRepository.findAllById(
                rules.stream().map(AssignmentRule::getLocationId).filter(java.util.Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Location::getId, Location::getName));
        Map<UUID, String> shifts = shiftRepository.findAllById(
                rules.stream().map(AssignmentRule::getShiftId).filter(java.util.Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Shift::getId, Shift::getName));
        List<AssignmentRuleDto> out = new ArrayList<>();
        for (AssignmentRule r : rules) {
            String targetName = engine.targetResolver(r.getDomain()).flatMap(t -> t.targetName(r.getTargetId())).orElse(null);
            out.add(AssignmentRuleDto.builder()
                    .id(r.getId()).domain(r.getDomain()).targetId(r.getTargetId()).targetName(targetName)
                    .name(r.getName()).priority(r.getPriority())
                    .departmentId(r.getDepartmentId()).departmentName(departments.get(r.getDepartmentId()))
                    .locationId(r.getLocationId()).locationName(locations.get(r.getLocationId()))
                    .shiftId(r.getShiftId()).shiftName(shifts.get(r.getShiftId()))
                    .employmentType(r.getEmploymentType()).grade(r.getGrade()).active(r.isActive())
                    .criteriaText(criteriaText(r, departments, locations, shifts))
                    .assignedCount(assignmentRepository.countByRuleIdAndEffectiveToIsNull(r.getId()))
                    .updatedAt(r.getUpdatedAt())
                    .build());
        }
        out.sort(Comparator.comparingInt(AssignmentRuleDto::getPriority));
        return out;
    }

    /** "Department = Engineering AND Location = Bangalore" - also used for the resolve explanation. */
    public static String criteriaText(AssignmentRule r, Map<UUID, String> departments, Map<UUID, String> locations, Map<UUID, String> shifts) {
        List<String> parts = new ArrayList<>();
        if (r.getDepartmentId() != null) parts.add("Department = " + departments.getOrDefault(r.getDepartmentId(), "?"));
        if (r.getLocationId() != null) parts.add("Location = " + locations.getOrDefault(r.getLocationId(), "?"));
        if (r.getShiftId() != null) parts.add("Shift = " + shifts.getOrDefault(r.getShiftId(), "?"));
        if (r.getEmploymentType() != null) parts.add("Employment type = " + r.getEmploymentType().replace('_', ' ').toLowerCase());
        if (r.getGrade() != null) parts.add("Grade = " + r.getGrade());
        return String.join(" AND ", parts);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
