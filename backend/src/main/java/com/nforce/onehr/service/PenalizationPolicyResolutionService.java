package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.config.PenalizationFallbackStrategy;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PenalizationPolicyAllocation;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PenalizationPolicyAllocationRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The one place "which Penalization Policy, and which version of it, governs this employee on
 * this date" is resolved — extracted from {@link ExceptionService} so every consumer that needs
 * to know whether a configured policy already covers a given discrepancy (the engine itself, and
 * {@link LatePenaltyService}'s legacy-fallback gate) agrees on the exact same answer. Two
 * independent re-derivations of this lookup is exactly the kind of drift that causes double
 * penalization (see LatePenaltyService's class javadoc).
 */
@Service
@RequiredArgsConstructor
public class PenalizationPolicyResolutionService {

    private final PenalizationPolicyVersionRepository penalizationPolicyVersionRepository;
    private final PenalizationPolicyAllocationRepository penalizationPolicyAllocationRepository;
    private final PenalizationPolicyService penalizationPolicyService;
    private final EmployeeRepository employeeRepository;
    private final AttendanceProperties attendanceProperties;

    /**
     * The Penalization Policy that governs this employee on {@code date}, resolved in priority
     * order: (1) the Organization Masters → Penalization Policy Allocation row effective on that
     * date (see {@link PenalizationPolicyAllocation}) — this is what makes "today Policy A,
     * tomorrow Policy B" possible; (2) the employee's legacy, non-dated
     * {@code employee.penalisationPolicy} assignment, for any employee never allocated through
     * that screen; (3) the org's default policy, resolved the exact same way
     * {@link PenalizationPolicyService#resolveDefaultPolicyId()} does. Without step (3), an
     * unassigned employee would fall through to
     * {@link PenalizationPolicyVersionRepository#findVersionsEffectiveAt} — an *unscoped* query
     * across every policy's version chain — which stopped being a safe "the one policy" lookup
     * the moment Policy List (Section 5) made multiple named policies possible: "ORDER BY version
     * DESC" with no policy filter can return an arbitrary policy's version, not the org's default.
     * Returns {@code null} only in the fully-degenerate case where no {@code PenalisationPolicy}
     * row exists at all (shouldn't happen given the V95 seed).
     *
     * <p>Gap-001: an INACTIVE policy is skipped at every tier — an explicit allocation or legacy
     * FK pointing at a policy that's since been deactivated falls through to the next tier exactly
     * as if that assignment didn't exist, rather than keeping a retired policy in force. Historical
     * {@link com.nforce.onehr.entity.AttendancePenalty} rows already carry their own policy/version
     * snapshot and are never re-resolved through this method, so past evaluations are unaffected.
     */
    public UUID resolveAssignedOrDefaultPolicyId(Employee employee, LocalDate date) {
        if (employee != null) {
            List<PenalizationPolicyAllocation> allocations =
                    penalizationPolicyAllocationRepository.findEffectiveAt(employee.getUserId(), date);
            if (!allocations.isEmpty() && isActivePolicy(allocations.get(0).getPenalisationPolicyId())) {
                return allocations.get(0).getPenalisationPolicyId();
            }
            if (employee.getPenalisationPolicy() != null && isActivePolicy(employee.getPenalisationPolicy().getId())) {
                return employee.getPenalisationPolicy().getId();
            }
        }
        return resolveDefaultPolicyIdOrNull();
    }

    private boolean isActivePolicy(UUID policyId) {
        return policyId != null && penalizationPolicyService.findActivePolicyIds().contains(policyId);
    }

    /**
     * Null-safe wrapper around {@link PenalizationPolicyService#resolveActiveDefaultPolicyId()} —
     * the one place every caller in this class (and any other authoritative-count consumer) gets
     * the org default from, instead of each re-deriving its own try/catch around the same call.
     *
     * <p>Section 7: under {@link PenalizationFallbackStrategy#REQUIRE_ALLOCATION}, there is
     * deliberately no fallback at all — an employee with no allocation and no legacy FK resolves
     * to {@code null} (surfaced as {@code resolvedPolicySource = "ALLOCATION_REQUIRED"} on the
     * Allocation screen) rather than silently picking up whatever policy happens to be flagged as
     * the org default.
     */
    private UUID resolveDefaultPolicyIdOrNull() {
        if (attendanceProperties.getPenalizationFallbackStrategy() == PenalizationFallbackStrategy.REQUIRE_ALLOCATION) {
            return null;
        }
        try {
            return penalizationPolicyService.resolveActiveDefaultPolicyId();
        } catch (IllegalStateException e) {
            // No ACTIVE PenalisationPolicy row exists — no default to fall back to; the caller's
            // null-handling takes over from here.
            return null;
        }
    }

    /**
     * The authoritative "which Penalization Policy currently governs this employee" answer for
     * EVERY non-deleted employee at once, keyed by employeeUserId — the exact same three-tier
     * priority as {@link #resolveAssignedOrDefaultPolicyId} (current allocation row > legacy FK >
     * org default), just computed in two bulk queries instead of one round trip per employee.
     * This is the single source every "how many employees currently have Policy X" surface
     * (Policy List's employee-count column, the Penalization Policy Allocation screen's policy
     * filter and per-row resolved policy) must read from — a second, independently-derived
     * implementation of this priority is exactly the kind of drift that let the two screens show
     * different counts for the same policy.
     */
    public Map<UUID, UUID> resolveCurrentPolicyIdsByEmployee(LocalDate date) {
        return resolveCurrentPolicyIdsByEmployee(date, resolveDefaultPolicyIdOrNull());
    }

    /**
     * Same as {@link #resolveCurrentPolicyIdsByEmployee(LocalDate)}, but for a caller that has
     * already resolved the org default policy id itself (e.g. from a policy list it already holds
     * in memory) — skips the extra {@link PenalizationPolicyService#resolveDefaultPolicyId()}
     * round trip that the no-arg overload would otherwise issue on every call.
     */
    public Map<UUID, UUID> resolveCurrentPolicyIdsByEmployee(LocalDate date, UUID defaultPolicyId) {
        // employeeUserId -> (policyId, createdAt) of whichever allocation row is effective on
        // `date` — createdAt is the same tie-break findEffectiveAt uses, kept here only as a
        // defensive guard: correct overlap prevention means at most one row is ever effective for
        // a given employee on a given date.
        Map<UUID, UUID> allocatedPolicyByEmployee = new HashMap<>();
        Map<UUID, LocalDateTime> allocatedAtByEmployee = new HashMap<>();
        for (Object[] row : penalizationPolicyAllocationRepository.findCurrentAllocationsAt(date)) {
            UUID employeeId = (UUID) row[0];
            UUID policyId = (UUID) row[1];
            LocalDateTime createdAt = (LocalDateTime) row[2];
            LocalDateTime existingCreatedAt = allocatedAtByEmployee.get(employeeId);
            if (existingCreatedAt == null || createdAt.isAfter(existingCreatedAt)) {
                allocatedPolicyByEmployee.put(employeeId, policyId);
                allocatedAtByEmployee.put(employeeId, createdAt);
            }
        }

        // Gap-001: same active-only rule as resolveAssignedOrDefaultPolicyId, computed once for
        // the whole bulk pass instead of one membership check per employee.
        Set<UUID> activePolicyIds = penalizationPolicyService.findActivePolicyIds();

        Map<UUID, UUID> resolved = new HashMap<>();
        for (Object[] row : employeeRepository.findAllEmployeeIdsWithLegacyPolicyId()) {
            UUID employeeId = (UUID) row[0];
            UUID legacyPolicyId = (UUID) row[1];
            UUID allocatedPolicyId = allocatedPolicyByEmployee.get(employeeId);
            UUID resolvedPolicyId = allocatedPolicyId != null && activePolicyIds.contains(allocatedPolicyId) ? allocatedPolicyId
                    : (legacyPolicyId != null && activePolicyIds.contains(legacyPolicyId) ? legacyPolicyId : defaultPolicyId);
            if (resolvedPolicyId != null) {
                resolved.put(employeeId, resolvedPolicyId);
            }
        }
        return resolved;
    }

    /** {@link #resolveCurrentPolicyIdsByEmployee(LocalDate)}, grouped into a per-policy count. */
    public Map<UUID, Long> resolveCurrentEmployeeCountsByPolicy(LocalDate date) {
        return resolveCurrentEmployeeCountsByPolicy(date, resolveDefaultPolicyIdOrNull());
    }

    /** {@link #resolveCurrentPolicyIdsByEmployee(LocalDate, UUID)}, grouped into a per-policy count. */
    public Map<UUID, Long> resolveCurrentEmployeeCountsByPolicy(LocalDate date, UUID defaultPolicyId) {
        Map<UUID, Long> counts = new HashMap<>();
        for (UUID policyId : resolveCurrentPolicyIdsByEmployee(date, defaultPolicyId).values()) {
            counts.merge(policyId, 1L, Long::sum);
        }
        return counts;
    }

    /** Single-policy convenience wrapper over {@link #resolveCurrentEmployeeCountsByPolicy}. */
    public long resolveCurrentEmployeeCount(UUID policyId, LocalDate date) {
        if (policyId == null) return 0L;
        return resolveCurrentEmployeeCountsByPolicy(date).getOrDefault(policyId, 0L);
    }

    public PenalizationPolicyVersion resolveEffectiveVersion(UUID assignedPolicyId, LocalDate date) {
        List<PenalizationPolicyVersion> candidates = assignedPolicyId != null
                ? penalizationPolicyVersionRepository.findVersionsEffectiveAtForPolicy(assignedPolicyId, date.atStartOfDay())
                : penalizationPolicyVersionRepository.findVersionsEffectiveAt(date.atStartOfDay());
        return candidates.stream().findFirst().orElse(null);
    }

    /** {@link #resolveAssignedOrDefaultPolicyId} followed by {@link #resolveEffectiveVersion} in one call. */
    public PenalizationPolicyVersion resolveEffectiveVersionForEmployee(Employee employee, LocalDate date) {
        return resolveEffectiveVersion(resolveAssignedOrDefaultPolicyId(employee, date), date);
    }
}
