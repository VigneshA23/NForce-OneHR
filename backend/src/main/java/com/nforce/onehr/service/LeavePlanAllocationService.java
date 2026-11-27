package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeavePlanDtos.AllocateRequest;
import com.nforce.onehr.dto.LeavePlanDtos.AllocationDto;
import com.nforce.onehr.dto.LeavePlanDtos.BulkAllocateRequest;
import com.nforce.onehr.dto.LeavePlanDtos.BulkRemoveRequest;
import com.nforce.onehr.dto.LeavePlanDtos.ConflictCheckRequest;
import com.nforce.onehr.dto.LeavePlanDtos.EmployeePlanDetail;
import com.nforce.onehr.dto.LeavePlanDtos.EmployeePlanRow;
import com.nforce.onehr.dto.LeavePlanDtos.UpdateAllocationRequest;
import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.dto.penalization.EmployeeAllocationProjection;
import com.nforce.onehr.entity.LeavePlan;
import com.nforce.onehr.entity.LeavePlanAllocation;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeavePlanAllocationRepository;
import com.nforce.onehr.repository.LeavePlanRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.assignment.AssignmentDomain;
import com.nforce.onehr.service.assignment.AssignmentOutcomeChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LV-01: assigning employees to leave plans — individually, in bulk, or by rule (the shared
 * assignment-rule engine, domain LEAVE_PLAN). Same model and rules as
 * {@link PenalizationPolicyAllocationService}: effective-dated rows, overlaps rejected (never silently
 * truncated), history never edited or removed, removing a current allocation ends it yesterday.
 * Resolution: allocation → rule → org default ({@link LeavePlanCatalog#planOf}).
 *
 * <p>Whenever an employee's plan changes (here, or when the rules' outcome changes) the months already
 * credited from the change on are re-credited under the new plan ({@link LeaveAccrualService#reconcile}),
 * which prorates the old and new entitlements by month.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeavePlanAllocationService {

    private final LeavePlanAllocationRepository allocationRepository;
    private final LeavePlanRepository planRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final LeavePlanRuleService planRuleService;
    private final LeaveAccrualService accrualService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final NotificationService notificationService;
    private final AttendanceProperties attendanceProperties;

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────

    /** Every non-deleted employee with the plan they are on today and why. Filtering happens in the browser. */
    @Transactional(readOnly = true)
    public List<EmployeePlanRow> employees() {
        LocalDate today = today();
        LeavePlanCatalog catalog = planRuleService.catalogForAll();
        List<UUID> ids = employeeRepository.findDirectoryRows().stream().map(r -> (UUID) r[0]).toList();
        if (ids.isEmpty()) return List.of();
        Map<UUID, List<LeavePlanAllocation>> allocations = allocationRepository.findByEmployeeUserIdIn(ids).stream()
                .collect(Collectors.groupingBy(LeavePlanAllocation::getEmployeeUserId));
        return employeeRepository.findAllocationProjectionsByIds(ids).stream()
                .map(p -> row(p, catalog, allocations.getOrDefault(p.getEmployeeUserId(), List.of()), today))
                .sorted(Comparator.comparing(EmployeePlanRow::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    @Transactional(readOnly = true)
    public EmployeePlanDetail employee(UUID employeeUserId) {
        LocalDate today = today();
        EmployeeAllocationProjection p = employeeRepository.findAllocationProjectionsByIds(List.of(employeeUserId)).stream()
                .findFirst().orElseThrow(() -> new NoSuchElementException("Employee not found"));
        List<LeavePlanAllocation> history = allocationRepository.findByEmployeeUserIdOrderByEffectiveFromDesc(employeeUserId);
        LeavePlanCatalog catalog = planRuleService.catalog(List.of(employeeUserId));
        return new EmployeePlanDetail(row(p, catalog, history, today), history.stream().map(a -> dto(a, catalog, today)).toList());
    }

    // ── Individual writes ───────────────────────────────────────────────────────────────────────

    @Transactional
    public AllocationDto allocate(AllocateRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        requireEmployee(req.employeeUserId());
        LeavePlan plan = requirePlan(req.planId());
        AllocationRanges.validate(req.effectiveFrom(), req.effectiveTo());
        checkOverlap(req.employeeUserId(), req.effectiveFrom(), req.effectiveTo(), null);
        LeavePlanAllocation a = allocationRepository.save(LeavePlanAllocation.builder()
                .employeeUserId(req.employeeUserId()).planId(plan.getId())
                .effectiveFrom(req.effectiveFrom()).effectiveTo(req.effectiveTo())
                .createdBy(actor.getId()).build());
        auditService.log(actor.getId(), "LEAVE_PLAN_ALLOCATION_ASSIGNED", a.getEmployeeUserId(), null, snapshot(a, plan.getName()));
        planChanged(List.of(a.getEmployeeUserId()), a.getEffectiveFrom());
        notifyAssigned(a, plan);
        return dto(a, plan.getName(), today());
    }

    @Transactional
    public AllocationDto update(UUID allocationId, UpdateAllocationRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeavePlanAllocation a = requireAllocation(allocationId);
        LocalDate today = today();
        if (AllocationRanges.HISTORICAL.equals(AllocationRanges.status(a.getEffectiveFrom(), a.getEffectiveTo(), today))) {
            throw new IllegalStateException("A historical allocation record cannot be edited.");
        }
        LeavePlan plan = requirePlan(req.planId());
        AllocationRanges.validate(req.effectiveFrom(), req.effectiveTo());
        checkOverlap(a.getEmployeeUserId(), req.effectiveFrom(), req.effectiveTo(), allocationId);
        boolean unchanged = plan.getId().equals(a.getPlanId()) && req.effectiveFrom().equals(a.getEffectiveFrom())
                && Objects.equals(req.effectiveTo(), a.getEffectiveTo());
        if (unchanged) return dto(a, plan.getName(), today);

        String before = snapshot(a, planName(a.getPlanId()));
        LocalDate changeFrom = min(a.getEffectiveFrom(), req.effectiveFrom());
        a.setPlanId(plan.getId());
        a.setEffectiveFrom(req.effectiveFrom());
        a.setEffectiveTo(req.effectiveTo());
        a.setUpdatedBy(actor.getId());
        a = allocationRepository.save(a);
        auditService.log(actor.getId(), "LEAVE_PLAN_ALLOCATION_UPDATED", a.getEmployeeUserId(), before, snapshot(a, plan.getName()));
        planChanged(List.of(a.getEmployeeUserId()), changeFrom);
        notifyAssigned(a, plan);
        return dto(a, plan.getName(), today);
    }

    @Transactional
    public void remove(UUID allocationId, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeavePlanAllocation a = requireAllocation(allocationId);
        LocalDate today = today();
        if (AllocationRanges.HISTORICAL.equals(AllocationRanges.status(a.getEffectiveFrom(), a.getEffectiveTo(), today))) {
            throw new IllegalStateException("A historical allocation record cannot be removed.");
        }
        String name = planName(a.getPlanId());
        String before = snapshot(a, name);
        boolean truncated = removeOrTruncate(a, actor.getId(), today);
        auditService.log(actor.getId(), "LEAVE_PLAN_ALLOCATION_REMOVED", a.getEmployeeUserId(), before,
                truncated ? snapshot(a, name) : null);
        planChanged(List.of(a.getEmployeeUserId()), today);
        notifyRemoved(a.getEmployeeUserId());
    }

    // ── Bulk writes ─────────────────────────────────────────────────────────────────────────────

    /** Each employee on their own: some may fail (e.g. an overlapping allocation) while the rest succeed. */
    @Transactional
    public AssignmentBulkResultResponse bulkAllocate(BulkAllocateRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LeavePlan plan = requirePlan(req.planId());
        AllocationRanges.validate(req.effectiveFrom(), req.effectiveTo());
        List<UUID> succeeded = new ArrayList<>();
        List<AssignmentBulkResultResponse.FailureDto> failed = new ArrayList<>();
        for (UUID employeeUserId : req.employeeUserIds().stream().distinct().toList()) {
            try {
                requireEmployee(employeeUserId);
                checkOverlap(employeeUserId, req.effectiveFrom(), req.effectiveTo(), null);
                LeavePlanAllocation a = allocationRepository.save(LeavePlanAllocation.builder()
                        .employeeUserId(employeeUserId).planId(plan.getId())
                        .effectiveFrom(req.effectiveFrom()).effectiveTo(req.effectiveTo())
                        .createdBy(actor.getId()).build());
                notifyAssigned(a, plan);
                succeeded.add(employeeUserId);
            } catch (IllegalStateException | IllegalArgumentException | NoSuchElementException e) {
                failed.add(AssignmentBulkResultResponse.FailureDto.builder().employeeUserId(employeeUserId).reason(e.getMessage()).build());
            }
        }
        auditService.log(actor.getId(), "LEAVE_PLAN_ALLOCATION_BULK_ASSIGNED", plan.getId(), null, bulkSnapshot(plan.getName(), succeeded, failed));
        planChanged(succeeded, req.effectiveFrom());
        return AssignmentBulkResultResponse.builder().succeededIds(succeeded).failed(failed).build();
    }

    /** Ends each employee's current allocation (they fall back to their rule or the org default). */
    @Transactional
    public AssignmentBulkResultResponse bulkRemove(BulkRemoveRequest req, String actorEmail) {
        User actor = requireActor(actorEmail);
        LocalDate today = today();
        List<UUID> succeeded = new ArrayList<>();
        List<AssignmentBulkResultResponse.FailureDto> failed = new ArrayList<>();
        for (UUID employeeUserId : req.employeeUserIds().stream().distinct().toList()) {
            Optional<LeavePlanAllocation> current = allocationRepository.findByEmployeeUserIdOrderByEffectiveFromDesc(employeeUserId)
                    .stream().filter(a -> a.covers(today)).findFirst();
            if (current.isEmpty()) {
                failed.add(AssignmentBulkResultResponse.FailureDto.builder().employeeUserId(employeeUserId)
                        .reason("No current allocation to remove").build());
                continue;
            }
            removeOrTruncate(current.get(), actor.getId(), today);
            notifyRemoved(employeeUserId);
            succeeded.add(employeeUserId);
        }
        auditService.log(actor.getId(), "LEAVE_PLAN_ALLOCATION_BULK_REMOVED", null, null, bulkSnapshot(null, succeeded, failed));
        planChanged(succeeded, today);
        return AssignmentBulkResultResponse.builder().succeededIds(succeeded).failed(failed).build();
    }

    /** Read-only preview of the overlap check: only employees that would conflict are returned. */
    @Transactional(readOnly = true)
    public Map<UUID, AllocationDto> checkConflicts(ConflictCheckRequest req) {
        LocalDate today = today();
        Map<UUID, AllocationDto> out = new LinkedHashMap<>();
        for (UUID employeeUserId : req.employeeUserIds()) {
            findConflict(employeeUserId, req.effectiveFrom(), req.effectiveTo(), req.excludeAllocationId())
                    .ifPresent(c -> out.put(employeeUserId, dto(c, planName(c.getPlanId()), today)));
        }
        return out;
    }

    // ── Rule outcome changes ────────────────────────────────────────────────────────────────────

    /** The rules moved an employee to another leave plan (or off one): re-credit from that day. */
    @EventListener
    public void onRuleOutcomeChanged(AssignmentOutcomeChangedEvent event) {
        if (!AssignmentDomain.LEAVE_PLAN.equals(event.domain())) return;
        try {
            planChanged(List.of(event.employeeUserId()), event.date());
        } catch (RuntimeException e) {
            // Never fail the employee save that triggered this; the months are re-credited on the next plan change.
            log.warn("Re-crediting leave after a plan rule change failed for {}: {}", event.employeeUserId(), e.getMessage());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private void planChanged(List<UUID> employeeUserIds, LocalDate from) {
        if (employeeUserIds.isEmpty()) return;
        planRuleService.invalidate();
        accrualService.reconcile(employeeUserIds, from, false);
    }

    private void checkOverlap(UUID employeeUserId, LocalDate from, LocalDate to, UUID excludeId) {
        findConflict(employeeUserId, from, to, excludeId).ifPresent(c -> {
            throw new IllegalStateException("This employee already has an allocation to \"" + planName(c.getPlanId())
                    + "\" covering " + c.getEffectiveFrom()
                    + (c.getEffectiveTo() != null ? " to " + c.getEffectiveTo() : " onward (no end date)")
                    + ". Edit or remove that allocation first.");
        });
    }

    private Optional<LeavePlanAllocation> findConflict(UUID employeeUserId, LocalDate from, LocalDate to, UUID excludeId) {
        return allocationRepository.findOverlapping(employeeUserId, from, to, excludeId).stream().findFirst();
    }

    /** A current allocation ends yesterday (history kept); one starting today or later is deleted. True = ended. */
    private boolean removeOrTruncate(LeavePlanAllocation a, UUID actorId, LocalDate today) {
        if (a.getEffectiveFrom().isBefore(today)) {
            a.setEffectiveTo(today.minusDays(1));
            a.setUpdatedBy(actorId);
            allocationRepository.save(a);
            return true;
        }
        allocationRepository.delete(a);
        return false;
    }

    private EmployeePlanRow row(EmployeeAllocationProjection p, LeavePlanCatalog catalog, List<LeavePlanAllocation> allocations,
                                LocalDate today) {
        LeavePlanCatalog.PlanResolution r = catalog.planOf(p.getEmployeeUserId(), today);
        LeavePlanAllocation current = allocations.stream().filter(a -> a.covers(today)).findFirst().orElse(null);
        LeavePlanAllocation upcoming = allocations.stream().filter(a -> a.getEffectiveFrom().isAfter(today))
                .min(Comparator.comparing(LeavePlanAllocation::getEffectiveFrom)).orElse(null);
        return new EmployeePlanRow(p.getEmployeeUserId(), p.getEmployeeCode(), p.getFullName(), p.isActive(),
                p.getDepartmentId(), p.getDepartmentName(), p.getLocationId(), p.getLocationName(),
                r != null ? r.planId() : null, r != null ? catalog.plan(r.planId()).map(LeavePlan::getName).orElse(null) : null,
                r != null ? r.source() : null, r != null ? r.ruleName() : null,
                current != null ? dto(current, catalog, today) : null, upcoming != null ? dto(upcoming, catalog, today) : null);
    }

    private AllocationDto dto(LeavePlanAllocation a, LeavePlanCatalog catalog, LocalDate today) {
        return dto(a, catalog.plan(a.getPlanId()).map(LeavePlan::getName).orElse("Unknown plan"), today);
    }

    private static AllocationDto dto(LeavePlanAllocation a, String planName, LocalDate today) {
        return new AllocationDto(a.getId(), a.getEmployeeUserId(), a.getPlanId(), planName, a.getEffectiveFrom(), a.getEffectiveTo(),
                AllocationRanges.status(a.getEffectiveFrom(), a.getEffectiveTo(), today), a.getCreatedAt());
    }

    private String snapshot(LeavePlanAllocation a, String planName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", planName);
        m.put("effectiveFrom", a.getEffectiveFrom());
        m.put("effectiveTo", a.getEffectiveTo());
        return auditSnapshot.toJson(m);
    }

    private String bulkSnapshot(String planName, List<UUID> succeeded, List<AssignmentBulkResultResponse.FailureDto> failed) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (planName != null) m.put("plan", planName);
        m.put("employeeCount", succeeded.size());
        m.put("succeededEmployeeIds", succeeded);
        m.put("failed", failed.stream().map(f -> Map.of("employeeUserId", f.getEmployeeUserId(), "reason", String.valueOf(f.getReason()))).toList());
        return auditSnapshot.toJson(m);
    }

    private void notifyAssigned(LeavePlanAllocation a, LeavePlan plan) {
        notificationService.send(a.getEmployeeUserId(), "LEAVE_PLAN_CHANGED", "Leave Plan Updated",
                "Your leave plan is \"" + plan.getName() + "\" from " + a.getEffectiveFrom()
                        + (a.getEffectiveTo() != null ? " to " + a.getEffectiveTo() : "") + ".", "/leave");
    }

    private void notifyRemoved(UUID employeeUserId) {
        String plan = Optional.ofNullable(planRuleService.catalog(List.of(employeeUserId)).planOf(employeeUserId, today()))
                .flatMap(r -> planRepository.findById(r.planId())).map(LeavePlan::getName).orElse(null);
        notificationService.send(employeeUserId, "LEAVE_PLAN_CHANGED", "Leave Plan Updated",
                "Your specific leave plan assignment has been removed." + (plan != null ? " You are now on \"" + plan + "\"." : ""),
                "/leave");
    }

    private String planName(UUID planId) {
        return planRepository.findById(planId).map(LeavePlan::getName).orElse("Unknown plan");
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private void requireEmployee(UUID employeeUserId) {
        if (!employeeRepository.existsById(employeeUserId)) throw new NoSuchElementException("Employee not found");
    }

    private LeavePlan requirePlan(UUID id) {
        return planRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Leave plan not found"));
    }

    private LeavePlanAllocation requireAllocation(UUID id) {
        return allocationRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Allocation not found"));
    }

    private User requireActor(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
    }
}
