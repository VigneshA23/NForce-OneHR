package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.config.PenalizationFallbackStrategy;
import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.dto.penalization.AllocationDto;
import com.nforce.onehr.dto.penalization.BulkAllocationRequest;
import com.nforce.onehr.dto.penalization.BulkRemoveAllocationRequest;
import com.nforce.onehr.dto.penalization.CreateAllocationRequest;
import com.nforce.onehr.dto.penalization.EmployeeAllocationDetailResponse;
import com.nforce.onehr.dto.penalization.EmployeeAllocationProjection;
import com.nforce.onehr.dto.penalization.EmployeeAllocationRow;
import com.nforce.onehr.dto.penalization.EmployeeAllocationSearchResponse;
import com.nforce.onehr.dto.penalization.PolicyResolutionDetailResponse;
import com.nforce.onehr.dto.penalization.UpdateAllocationRequest;
import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.PenalizationPolicyAllocation;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.EmployeeSpecifications;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.PenalizationPolicyAllocationRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Organization Masters → Penalization Policy → Penalization Policy Allocation: the org-wide
 * screen for assigning employees to a {@link PenalisationPolicy} with an effective date range,
 * on top of the legacy single {@code employee.penalisationPolicy} FK. This table is what makes
 * {@link PenalizationPolicyResolutionService} date-aware — "today Policy A, tomorrow Policy B".
 *
 * <p>Overlap handling is deliberately "prevent, don't auto-resolve": creating or editing an
 * allocation that would overlap another one of the same employee's rows is rejected with the
 * conflicting row's details, rather than silently truncating or deleting that row. History rows
 * (effectiveTo in the past) can never be edited or removed — they are a permanent record of what
 * actually governed a past attendance evaluation.
 */
@Service
@RequiredArgsConstructor
public class PenalizationPolicyAllocationService {

    private static final DateTimeFormatter NOTIFICATION_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final PenalizationPolicyAllocationRepository allocationRepository;
    private final EmployeeRepository employeeRepository;
    private final PenalisationPolicyRepository penalisationPolicyRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final PenalizationPolicyResolutionService resolutionService;
    private final EmployeeService employeeService;
    private final AttendanceProperties attendanceProperties;

    private LocalDate today() {
        return LocalDateTime.now(ZoneId.of(attendanceProperties.getZone())).toLocalDate();
    }

    // ── Search ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public EmployeeAllocationSearchResponse searchEmployees(UUID businessUnitId, UUID departmentId, UUID locationId,
                                                             UUID penalisationPolicyId, String search, int page, int size,
                                                             boolean all) {
        LocalDate today = today();
        Specification<Employee> spec = Specification.where(EmployeeSpecifications.notDeleted())
                .and(EmployeeSpecifications.businessUnitIdEquals(businessUnitId))
                .and(EmployeeSpecifications.departmentIdEquals(departmentId))
                .and(EmployeeSpecifications.locationIdEquals(locationId))
                .and(EmployeeSpecifications.searchTextMatches(search));

        // "Which employees currently have Policy X" is answered by the exact same authoritative
        // resolution the Policy List's employee count and the attendance engine use — not a
        // second, independently-derived filter. See PenalizationPolicyResolutionService
        // #resolveCurrentPolicyIdsByEmployee.
        Map<UUID, UUID> resolvedPolicyByEmployee = resolutionService.resolveCurrentPolicyIdsByEmployee(today);
        if (penalisationPolicyId != null) {
            spec = spec.and(EmployeeSpecifications.userIdIn(matchingEmployeeIds(penalisationPolicyId, resolvedPolicyByEmployee)));
        }

        // Pageable.unpaged() is still a single, indexed, LIMIT/OFFSET-free query — not a "load
        // everything then filter in Java" fallback — so the Allocation table's "show every
        // employee, no pagination" requirement doesn't cost anything beyond one larger result set
        // (and skips the separate COUNT query PageRequest would otherwise issue).
        // The Add Employees modal keeps calling with all=false, so its own pagination is untouched.
        Pageable pageable = all ? Pageable.unpaged(Sort.by(Sort.Direction.ASC, "fullName"))
                : PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "fullName"));
        Page<Employee> idPage = employeeRepository.findAll(spec, pageable);

        List<UUID> pageIds = idPage.getContent().stream().map(Employee::getUserId).toList();
        // Re-fetch this page's rows via a purpose-built projection — only the scalar columns the
        // Allocation row actually reads, not full BusinessUnit/Department/Designation/Location/User
        // entity graphs via JOIN FETCH. findAll(Specification, Pageable) above only guarantees
        // correct filtering/count/ordering, not that those associations are eagerly loaded, so
        // building rows straight off its content would lazy-load each of those once per row.
        Map<UUID, EmployeeAllocationProjection> hydratedById = pageIds.isEmpty() ? Map.of()
                : employeeRepository.findAllocationProjectionsByIds(pageIds).stream()
                        .collect(Collectors.toMap(EmployeeAllocationProjection::getEmployeeUserId, e -> e));
        List<EmployeeAllocationProjection> hydrated = pageIds.stream().map(hydratedById::get).filter(Objects::nonNull).toList();

        Map<UUID, List<PenalizationPolicyAllocation>> allocationsByEmployee = pageIds.isEmpty()
                ? Map.of()
                : allocationRepository.findByEmployeeUserIdIn(pageIds).stream()
                        .collect(Collectors.groupingBy(PenalizationPolicyAllocation::getEmployeeUserId));
        Map<UUID, EmployeeResponse.ManagerRef> managersByEmployee =
                pageIds.isEmpty() ? Map.of() : employeeService.findCurrentManagersBulk(pageIds);

        Map<UUID, PenalisationPolicy> policyCache = new LinkedHashMap<>();
        List<EmployeeAllocationRow> rows = hydrated.stream()
                .map(e -> toRow(e, allocationsByEmployee.getOrDefault(e.getEmployeeUserId(), List.of()), today, policyCache,
                        resolvedPolicyByEmployee.get(e.getEmployeeUserId()), managersByEmployee.get(e.getEmployeeUserId())))
                .toList();

        return EmployeeAllocationSearchResponse.builder()
                .content(rows).totalElements(idPage.getTotalElements()).totalPages(idPage.getTotalPages())
                .page(page).size(size).build();
    }

    @Transactional(readOnly = true)
    public EmployeeAllocationDetailResponse getEmployeeDetail(UUID employeeUserId) {
        Employee employee = findEmployee(employeeUserId);
        List<PenalizationPolicyAllocation> history = allocationRepository.findByEmployeeUserIdOrderByEffectiveFromDesc(employeeUserId);
        LocalDate today = today();
        Map<UUID, PenalisationPolicy> policyCache = new LinkedHashMap<>();
        UUID resolvedPolicyId = resolutionService.resolveAssignedOrDefaultPolicyId(employee, today);
        EmployeeResponse.ManagerRef manager =
                employeeService.findCurrentManagersBulk(List.of(employeeUserId)).get(employeeUserId);
        EmployeeAllocationRow row = toRow(employee, history, today, policyCache, resolvedPolicyId, manager);

        return EmployeeAllocationDetailResponse.builder()
                .employeeUserId(employee.getUserId()).employeeCode(employee.getEmployeeCode()).fullName(employee.getFullName())
                .email(employee.getUser().getEmail()).active(employee.getUser().isActive())
                .designationTitle(employee.getDesignation() != null ? employee.getDesignation().getTitle() : null)
                .businessUnitName(employee.getBusinessUnit() != null ? employee.getBusinessUnit().getName() : null)
                .departmentName(employee.getDepartment() != null ? employee.getDepartment().getName() : null)
                .locationName(employee.getLocation() != null ? employee.getLocation().getName() : null)
                .reportingManagerId(manager != null ? UUID.fromString(manager.getUserId()) : null)
                .reportingManagerName(manager != null ? manager.getFullName() : null)
                .resolvedPolicyId(row.getResolvedPolicyId()).resolvedPolicyName(row.getResolvedPolicyName())
                .resolvedPolicySource(row.getResolvedPolicySource())
                .history(history.stream().map(a -> toAllocationDto(a, today, policyCache)).toList())
                .build();
    }

    /**
     * Section 21: "Employee X on date Y → which policy applies?" for an ARBITRARY date, not just
     * today — reuses {@link #toRow} (the exact same allocation/legacy/default/resolvedPolicySource
     * logic {@link #getEmployeeDetail} and every search row already use), adding the version and
     * "why null" detail neither of those needs. The attendance engine's own resolution
     * ({@link PenalizationPolicyResolutionService#resolveEffectiveVersionForEmployee}) and this API
     * are guaranteed to agree because both call the identical {@code resolveAssignedOrDefaultPolicyId}
     * this method calls.
     */
    @Transactional(readOnly = true)
    public PolicyResolutionDetailResponse resolveFor(UUID employeeUserId, LocalDate date) {
        Employee employee = findEmployee(employeeUserId);
        List<PenalizationPolicyAllocation> allocations = allocationRepository.findByEmployeeUserIdOrderByEffectiveFromDesc(employeeUserId);
        Map<UUID, PenalisationPolicy> policyCache = new LinkedHashMap<>();
        UUID resolvedPolicyId = resolutionService.resolveAssignedOrDefaultPolicyId(employee, date);
        EmployeeResponse.ManagerRef manager =
                employeeService.findCurrentManagersBulk(List.of(employeeUserId)).get(employeeUserId);
        EmployeeAllocationRow row = toRow(employee, allocations, date, policyCache, resolvedPolicyId, manager);

        PenalizationPolicyVersion version = resolvedPolicyId != null
                ? resolutionService.resolveEffectiveVersion(resolvedPolicyId, date) : null;
        PenalisationPolicy resolvedPolicy = resolvedPolicyId != null ? policyCache.get(resolvedPolicyId) : null;

        return PolicyResolutionDetailResponse.builder()
                .employeeUserId(employeeUserId).date(date)
                .resolvedPolicyId(resolvedPolicyId).resolvedPolicyName(row.getResolvedPolicyName())
                .resolvedPolicySource(row.getResolvedPolicySource())
                .policyStatus(resolvedPolicy != null ? resolvedPolicy.getStatus() : null)
                .policyVersion(version != null ? version.getVersion() : null)
                .versionEffectiveFrom(version != null ? version.getEffectiveFrom() : null)
                .currentAllocation(row.getCurrentAllocation())
                .reason(resolvedPolicyId == null ? noResolvedPolicyReason() : null)
                .build();
    }

    private String noResolvedPolicyReason() {
        if (attendanceProperties.getPenalizationFallbackStrategy() == PenalizationFallbackStrategy.REQUIRE_ALLOCATION) {
            return "No allocation or legacy assignment exists for this employee on this date, and the "
                    + "REQUIRE_ALLOCATION fallback strategy means no organization default is consulted.";
        }
        return "No allocation or legacy assignment exists for this employee on this date, and no active "
                + "organization default policy is configured.";
    }

    // ── Individual write operations ──────────────────────────────────────────────

    @Transactional
    public AllocationDto allocate(CreateAllocationRequest req, String actorEmail) {
        User actor = resolveActor(actorEmail);
        Employee employee = findEmployee(req.getEmployeeUserId());
        PenalisationPolicy policy = findPolicy(req.getPenalisationPolicyId());
        validateRange(req.getEffectiveFrom(), req.getEffectiveTo());
        checkOverlap(employee.getUserId(), req.getEffectiveFrom(), req.getEffectiveTo(), null);

        PenalizationPolicyAllocation allocation = allocationRepository.save(PenalizationPolicyAllocation.builder()
                .employeeUserId(employee.getUserId()).penalisationPolicyId(policy.getId())
                .effectiveFrom(req.getEffectiveFrom()).effectiveTo(req.getEffectiveTo())
                .createdBy(actor.getId())
                .build());

        auditService.log(actor.getId(), "PENALIZATION_POLICY_ALLOCATION_ASSIGNED", employee.getUserId(),
                null, allocationSnapshotJson(allocation, policy.getName()));
        notifyEmployee(employee, policy, allocation);
        return toAllocationDto(allocation, today(), null);
    }

    @Transactional
    public AllocationDto update(UUID allocationId, UpdateAllocationRequest req, String actorEmail) {
        User actor = resolveActor(actorEmail);
        PenalizationPolicyAllocation allocation = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new NoSuchElementException("Allocation not found"));
        LocalDate today = today();
        if ("HISTORICAL".equals(status(allocation, today))) {
            throw new IllegalStateException("A historical allocation record cannot be edited.");
        }
        PenalisationPolicy policy = findPolicy(req.getPenalisationPolicyId());
        validateRange(req.getEffectiveFrom(), req.getEffectiveTo());
        checkOverlap(allocation.getEmployeeUserId(), req.getEffectiveFrom(), req.getEffectiveTo(), allocationId);

        boolean unchanged = policy.getId().equals(allocation.getPenalisationPolicyId())
                && req.getEffectiveFrom().equals(allocation.getEffectiveFrom())
                && Objects.equals(req.getEffectiveTo(), allocation.getEffectiveTo());
        if (unchanged) {
            return toAllocationDto(allocation, today, null);
        }

        String before = allocationSnapshotJson(allocation, resolvePolicyName(allocation.getPenalisationPolicyId()));
        allocation.setPenalisationPolicyId(policy.getId());
        allocation.setEffectiveFrom(req.getEffectiveFrom());
        allocation.setEffectiveTo(req.getEffectiveTo());
        allocation.setUpdatedBy(actor.getId());
        PenalizationPolicyAllocation saved = allocationRepository.save(allocation);

        auditService.log(actor.getId(), "PENALIZATION_POLICY_ALLOCATION_UPDATED", saved.getEmployeeUserId(),
                before, allocationSnapshotJson(saved, policy.getName()));
        // Only a genuine change is worth telling the employee about — a save that resubmits the
        // exact same policy/date-range is a no-op and must not fire a duplicate notification.
        employeeRepository.findById(saved.getEmployeeUserId())
                .ifPresent(employee -> notifyEmployee(employee, policy, saved));
        return toAllocationDto(saved, today, null);
    }

    @Transactional
    public void remove(UUID allocationId, String actorEmail) {
        User actor = resolveActor(actorEmail);
        PenalizationPolicyAllocation allocation = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new NoSuchElementException("Allocation not found"));
        LocalDate today = today();
        if ("HISTORICAL".equals(status(allocation, today))) {
            throw new IllegalStateException("A historical allocation record cannot be removed.");
        }
        String policyName = resolvePolicyName(allocation.getPenalisationPolicyId());
        String before = allocationSnapshotJson(allocation, policyName);
        boolean truncated = allocation.getEffectiveFrom().isBefore(today);
        removeOrTruncate(allocation, actor.getId(), today);
        auditService.log(actor.getId(), "PENALIZATION_POLICY_ALLOCATION_REMOVED", allocation.getEmployeeUserId(),
                before, truncated ? allocationSnapshotJson(allocation, policyName) : null);
        employeeRepository.findById(allocation.getEmployeeUserId()).ifPresent(this::notifyRemoval);
    }

    // ── Bulk write operations ─────────────────────────────────────────────────────

    @Transactional
    public AssignmentBulkResultResponse bulkAllocate(BulkAllocationRequest req, String actorEmail) {
        User actor = resolveActor(actorEmail);
        PenalisationPolicy policy = findPolicy(req.getPenalisationPolicyId());
        validateRange(req.getEffectiveFrom(), req.getEffectiveTo());

        List<UUID> succeeded = new ArrayList<>();
        List<AssignmentBulkResultResponse.FailureDto> failed = new ArrayList<>();
        for (UUID employeeUserId : req.getEmployeeUserIds()) {
            try {
                Employee employee = findEmployee(employeeUserId);
                checkOverlap(employeeUserId, req.getEffectiveFrom(), req.getEffectiveTo(), null);
                PenalizationPolicyAllocation allocation = allocationRepository.save(PenalizationPolicyAllocation.builder()
                        .employeeUserId(employeeUserId).penalisationPolicyId(policy.getId())
                        .effectiveFrom(req.getEffectiveFrom()).effectiveTo(req.getEffectiveTo())
                        .createdBy(actor.getId())
                        .build());
                notifyEmployee(employee, policy, allocation);
                succeeded.add(employeeUserId);
            } catch (Exception e) {
                failed.add(AssignmentBulkResultResponse.FailureDto.builder()
                        .employeeUserId(employeeUserId).reason(e.getMessage()).build());
            }
        }
        auditService.log(actor.getId(), "PENALIZATION_POLICY_ALLOCATION_BULK_ASSIGNED", policy.getId(),
                null, bulkAuditJson(succeeded, failed));
        return AssignmentBulkResultResponse.builder().succeededIds(succeeded).failed(failed).build();
    }

    @Transactional
    public AssignmentBulkResultResponse bulkRemove(BulkRemoveAllocationRequest req, String actorEmail) {
        User actor = resolveActor(actorEmail);
        LocalDate today = today();
        List<UUID> succeeded = new ArrayList<>();
        List<AssignmentBulkResultResponse.FailureDto> failed = new ArrayList<>();
        // Gap-039: unlike bulkAllocate, this request carries no single policyId — each employee's
        // current allocation can point at a different policy — so the one shared value bulkAllocate
        // uses as its audit target isn't available here by construction, not by oversight.
        Set<UUID> removedPolicyIds = new HashSet<>();
        for (UUID employeeUserId : req.getEmployeeUserIds()) {
            try {
                List<PenalizationPolicyAllocation> current = allocationRepository.findEffectiveAt(employeeUserId, today);
                if (current.isEmpty()) {
                    throw new IllegalStateException("No active allocation to remove");
                }
                PenalizationPolicyAllocation allocation = current.get(0);
                removedPolicyIds.add(allocation.getPenalisationPolicyId());
                removeOrTruncate(allocation, actor.getId(), today);
                succeeded.add(employeeUserId);
                employeeRepository.findById(employeeUserId).ifPresent(this::notifyRemoval);
            } catch (Exception e) {
                failed.add(AssignmentBulkResultResponse.FailureDto.builder()
                        .employeeUserId(employeeUserId).reason(e.getMessage()).build());
            }
        }
        // A single policy was removed from everyone (the common case — e.g. "clear this policy's
        // allocations") is the one scenario with a genuine bulk-allocate-style target; anything
        // else (a mixed-policy selection, or nothing succeeded) has no single meaningful target,
        // so this is left null rather than misattributing the event to the actor auditing themselves.
        UUID auditTargetId = removedPolicyIds.size() == 1 ? removedPolicyIds.iterator().next() : null;
        auditService.log(actor.getId(), "PENALIZATION_POLICY_ALLOCATION_BULK_REMOVED", auditTargetId,
                null, bulkAuditJson(succeeded, failed));
        return AssignmentBulkResultResponse.builder().succeededIds(succeeded).failed(failed).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /**
     * Section 9: a bulk operation's audit entry must let an HR/audit user determine exactly which
     * employees were affected, not just how many — {@code employeeCount} alone (the previous
     * behavior) can't answer "was employee X part of this batch?". Includes every failure's own
     * employee id and reason too, so a partially-successful batch is fully explainable from the
     * audit log alone.
     */
    private String bulkAuditJson(List<UUID> succeeded, List<AssignmentBulkResultResponse.FailureDto> failed) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("employeeCount", succeeded.size());
        snapshot.put("succeededEmployeeIds", succeeded);
        snapshot.put("failed", failed.stream()
                .map(f -> Map.of("employeeUserId", f.getEmployeeUserId(), "reason", String.valueOf(f.getReason())))
                .toList());
        return auditSnapshot.toJson(snapshot);
    }

    /**
     * Every employee id the shared {@code resolvedPolicyByEmployee} map (from
     * {@link PenalizationPolicyResolutionService#resolveCurrentPolicyIdsByEmployee}) resolves to
     * {@code policyId} today — extracted as its own method (rather than inlined in
     * {@link #searchEmployees}) specifically so it can be asserted against directly, independent
     * of the DB round trip, to prove the Allocation screen's "filter by policy" set is exactly the
     * Policy List's/attendance engine's authoritative count for that policy — never a second,
     * independently-derived answer.
     */
    Set<UUID> matchingEmployeeIds(UUID policyId, Map<UUID, UUID> resolvedPolicyByEmployee) {
        return resolvedPolicyByEmployee.entrySet().stream()
                .filter(e -> policyId.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("Effective To cannot be before Effective From");
        }
    }

    /** Non-destructive overlap prevention: reject rather than silently truncate/delete the conflicting row. */
    private void checkOverlap(UUID employeeUserId, LocalDate from, LocalDate to, UUID excludeId) {
        findConflict(employeeUserId, from, to, excludeId).ifPresent(conflict -> {
            String conflictPolicyName = resolvePolicyName(conflict.getPenalisationPolicyId());
            throw new IllegalStateException("This employee already has an allocation to \"" + conflictPolicyName
                    + "\" covering " + conflict.getEffectiveFrom()
                    + (conflict.getEffectiveTo() != null ? " to " + conflict.getEffectiveTo() : " onward (no end date)")
                    + ". Edit or remove that allocation first.");
        });
    }

    private Optional<PenalizationPolicyAllocation> findConflict(UUID employeeUserId, LocalDate from, LocalDate to, UUID excludeId) {
        List<PenalizationPolicyAllocation> overlapping = allocationRepository.findOverlapping(employeeUserId, from, to, excludeId);
        return overlapping.isEmpty() ? Optional.empty() : Optional.of(overlapping.get(0));
    }

    /**
     * Section 16 (Gap-016): a read-only preview of the exact same overlap check
     * {@link #checkOverlap} enforces at write time, so the Allocation screen can warn an admin
     * about a conflict before they submit rather than only after. Only employees that actually
     * conflict appear in the returned map.
     */
    @Transactional(readOnly = true)
    public Map<UUID, AllocationDto> checkConflicts(List<UUID> employeeUserIds, LocalDate from, LocalDate to, UUID excludeAllocationId) {
        LocalDate today = today();
        Map<UUID, PenalisationPolicy> policyCache = new LinkedHashMap<>();
        Map<UUID, AllocationDto> conflicts = new LinkedHashMap<>();
        for (UUID employeeUserId : employeeUserIds) {
            findConflict(employeeUserId, from, to, excludeAllocationId)
                    .ifPresent(conflict -> conflicts.put(employeeUserId, toAllocationDto(conflict, today, policyCache)));
        }
        return conflicts;
    }

    /**
     * Removing a CURRENT allocation truncates its {@code effectiveTo} to yesterday instead of
     * deleting the row, so "this employee was under this policy from X to yesterday" remains a
     * permanent, recoverable historical record — a hard delete here would erase the only record
     * of what actually governed this employee's attendance evaluation during that window (the
     * allocation removal/history rule). An allocation whose {@code effectiveFrom} is today or
     * later (a FUTURE allocation, or one that started today and therefore has zero *completed*
     * days in effect) has no history to preserve and is genuinely deleted instead.
     */
    private void removeOrTruncate(PenalizationPolicyAllocation allocation, UUID actorId, LocalDate today) {
        if (allocation.getEffectiveFrom().isBefore(today)) {
            allocation.setEffectiveTo(today.minusDays(1));
            allocation.setUpdatedBy(actorId);
            allocationRepository.save(allocation);
        } else {
            allocationRepository.delete(allocation);
        }
    }

    private String status(PenalizationPolicyAllocation a, LocalDate today) {
        if (a.getEffectiveTo() != null && a.getEffectiveTo().isBefore(today)) return "HISTORICAL";
        if (a.getEffectiveFrom().isAfter(today)) return "FUTURE";
        return "CURRENT";
    }

    private Employee findEmployee(UUID employeeUserId) {
        return employeeRepository.findById(employeeUserId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
    }

    private PenalisationPolicy findPolicy(UUID policyId) {
        return penalisationPolicyRepository.findById(policyId)
                .orElseThrow(() -> new NoSuchElementException("Penalization policy not found"));
    }

    private String resolvePolicyName(UUID policyId) {
        return penalisationPolicyRepository.findById(policyId).map(PenalisationPolicy::getName).orElse("Unknown Policy");
    }

    private String policyName(UUID policyId, Map<UUID, PenalisationPolicy> cache) {
        if (policyId == null) return null;
        if (cache == null) return resolvePolicyName(policyId);
        PenalisationPolicy cached = cache.computeIfAbsent(policyId, id -> penalisationPolicyRepository.findById(id).orElse(null));
        return cached != null ? cached.getName() : "Unknown Policy";
    }

    private User resolveActor(String actorEmail) {
        return userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private String allocationSnapshotJson(PenalizationPolicyAllocation a, String policyName) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("policy", policyName);
        snapshot.put("effectiveFrom", a.getEffectiveFrom());
        snapshot.put("effectiveTo", a.getEffectiveTo());
        return auditSnapshot.toJson(snapshot);
    }

    private AllocationDto toAllocationDto(PenalizationPolicyAllocation a, LocalDate today, Map<UUID, PenalisationPolicy> cache) {
        return AllocationDto.builder()
                .id(a.getId()).penalisationPolicyId(a.getPenalisationPolicyId())
                .penalisationPolicyName(policyName(a.getPenalisationPolicyId(), cache))
                .effectiveFrom(a.getEffectiveFrom()).effectiveTo(a.getEffectiveTo()).status(status(a, today))
                .createdBy(a.getCreatedBy()).createdAt(a.getCreatedAt())
                .updatedBy(a.getUpdatedBy()).updatedAt(a.getUpdatedAt())
                .build();
    }

    /**
     * {@code resolvedPolicyId} is passed in — always read from
     * {@link PenalizationPolicyResolutionService}, never re-derived here — so this row's
     * "governed by" display can never drift from the authoritative count/resolution. {@code
     * current}/{@code upcoming} (used only for the row's own display detail — dates, id, source
     * label) are still found locally against the employee's already-fetched allocation list,
     * using the identical effective-date predicate the resolution service's bulk query applies.
     */
    private EmployeeAllocationRow toRow(Employee employee, List<PenalizationPolicyAllocation> allocations, LocalDate today,
                                         Map<UUID, PenalisationPolicy> cache, UUID resolvedPolicyId,
                                         EmployeeResponse.ManagerRef manager) {
        return buildRow(employee.getUserId(), employee.getEmployeeCode(), employee.getFullName(),
                employee.getUser().getEmail(), employee.getUser().isActive(),
                employee.getDesignation() != null ? employee.getDesignation().getTitle() : null,
                employee.getBusinessUnit() != null ? employee.getBusinessUnit().getId() : null,
                employee.getBusinessUnit() != null ? employee.getBusinessUnit().getName() : null,
                employee.getDepartment() != null ? employee.getDepartment().getId() : null,
                employee.getDepartment() != null ? employee.getDepartment().getName() : null,
                employee.getLocation() != null ? employee.getLocation().getId() : null,
                employee.getLocation() != null ? employee.getLocation().getName() : null,
                employee.getPenalisationPolicy() != null,
                allocations, today, cache, resolvedPolicyId, manager);
    }

    /**
     * Bulk-search variant of {@link #toRow(Employee, List, LocalDate, Map, UUID, EmployeeResponse.ManagerRef)}
     * — same row-building logic, fed from {@link EmployeeAllocationProjection}'s already-flat
     * scalar fields instead of a full {@link Employee} entity graph. See
     * {@link com.nforce.onehr.repository.EmployeeRepository#findAllocationProjectionsByIds}.
     */
    private EmployeeAllocationRow toRow(EmployeeAllocationProjection p, List<PenalizationPolicyAllocation> allocations, LocalDate today,
                                         Map<UUID, PenalisationPolicy> cache, UUID resolvedPolicyId,
                                         EmployeeResponse.ManagerRef manager) {
        return buildRow(p.getEmployeeUserId(), p.getEmployeeCode(), p.getFullName(), p.getEmail(), p.isActive(),
                p.getDesignationTitle(), p.getBusinessUnitId(), p.getBusinessUnitName(),
                p.getDepartmentId(), p.getDepartmentName(), p.getLocationId(), p.getLocationName(),
                p.getLegacyPolicyId() != null,
                allocations, today, cache, resolvedPolicyId, manager);
    }

    private EmployeeAllocationRow buildRow(UUID employeeUserId, String employeeCode, String fullName, String email, boolean active,
                                            String designationTitle, UUID businessUnitId, String businessUnitName,
                                            UUID departmentId, String departmentName, UUID locationId, String locationName,
                                            boolean hasLegacyPolicy,
                                            List<PenalizationPolicyAllocation> allocations, LocalDate today,
                                            Map<UUID, PenalisationPolicy> cache, UUID resolvedPolicyId,
                                            EmployeeResponse.ManagerRef manager) {
        PenalizationPolicyAllocation current = allocations.stream()
                .filter(a -> !a.getEffectiveFrom().isAfter(today) && (a.getEffectiveTo() == null || !a.getEffectiveTo().isBefore(today)))
                .max(Comparator.comparing(PenalizationPolicyAllocation::getCreatedAt))
                .orElse(null);
        PenalizationPolicyAllocation upcoming = allocations.stream()
                .filter(a -> a.getEffectiveFrom().isAfter(today))
                .min(Comparator.comparing(PenalizationPolicyAllocation::getEffectiveFrom))
                .orElse(null);

        // Section 7: a null resolvedPolicyId here means the REQUIRE_ALLOCATION strategy is active
        // and this employee has no allocation and no legacy FK — surfaced distinctly so HR/admin
        // can find and fix the gap, rather than being mislabeled "DEFAULT" when nothing was
        // actually resolved.
        String resolvedSource = resolvedPolicyId == null ? "ALLOCATION_REQUIRED"
                : current != null ? "ALLOCATION" : hasLegacyPolicy ? "LEGACY" : "DEFAULT";

        return EmployeeAllocationRow.builder()
                .employeeUserId(employeeUserId).employeeCode(employeeCode).fullName(fullName)
                .email(email).active(active)
                .designationTitle(designationTitle)
                .businessUnitId(businessUnitId).businessUnitName(businessUnitName)
                .departmentId(departmentId).departmentName(departmentName)
                .locationId(locationId).locationName(locationName)
                .reportingManagerId(manager != null ? UUID.fromString(manager.getUserId()) : null)
                .reportingManagerName(manager != null ? manager.getFullName() : null)
                .resolvedPolicyId(resolvedPolicyId).resolvedPolicyName(policyName(resolvedPolicyId, cache))
                .resolvedPolicySource(resolvedSource)
                .currentAllocation(current != null ? toAllocationDto(current, today, cache) : null)
                .upcomingAllocation(upcoming != null ? toAllocationDto(upcoming, today, cache) : null)
                .build();
    }

    private void notifyEmployee(Employee employee, PenalisationPolicy policy, PenalizationPolicyAllocation allocation) {
        String message = "Your attendance penalization policy has been set to \"" + policy.getName() + "\", effective from "
                + allocation.getEffectiveFrom().format(NOTIFICATION_DATE_FMT)
                + (allocation.getEffectiveTo() != null ? " to " + allocation.getEffectiveTo().format(NOTIFICATION_DATE_FMT) : "")
                + ". Attendance outside this range continues to be evaluated under your previous configuration.";
        notificationService.send(employee.getUserId(), "PENALIZATION_POLICY_CHANGED",
                "Attendance Penalization Policy Updated", message, "/attendance");
    }

    private void notifyRemoval(Employee employee) {
        notificationService.send(employee.getUserId(), "PENALIZATION_POLICY_CHANGED",
                "Attendance Penalization Policy Updated",
                "Your specific attendance penalization policy allocation has been removed. "
                        + "You now follow your organization's default configuration.",
                "/attendance");
    }
}
