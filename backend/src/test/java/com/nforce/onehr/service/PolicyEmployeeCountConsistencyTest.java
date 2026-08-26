package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.penalization.PenalisationPolicySummaryDto;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.PenalizationPolicyAllocationRepository;
import com.nforce.onehr.repository.PenalizationPolicyLateHoursTierRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The fix for the bug report: the Policy List and the Penalization Policy Allocation screen used
 * to compute "how many employees currently have Policy X" two different ways (legacy-FK-only vs
 * allocation+legacy), so the same policy could show two different counts. Both now read from the
 * single {@link PenalizationPolicyResolutionService#resolveCurrentPolicyIdsByEmployee} answer —
 * this test wires up REAL instances of {@link PenalisationPolicyManagementService} (Policy List),
 * {@link PenalizationPolicyAllocationService} (Allocation screen), and a REAL, shared
 * {@link PenalizationPolicyResolutionService} against one set of mocked employee/allocation data,
 * and proves all three agree on the same date.
 *
 * <p>Scope note: like every other test in this suite, this stops at the service layer — the
 * database-level Specification the Allocation screen ultimately runs against Postgres is not
 * exercised here (no test in this codebase runs against a real database). What this test does
 * prove for real is the part that caused the reported bug: the *set of employee ids* each screen
 * treats as "currently on this policy" is derived from the exact same map, not two independent
 * re-derivations of the allocation/legacy-FK/default priority.
 */
@ExtendWith(MockitoExtension.class)
class PolicyEmployeeCountConsistencyTest {

    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyAllocationRepository allocationRepository;
    @Mock private PenalizationPolicyService penalizationPolicyService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;
    @Mock private PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;
    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private NotificationService notificationService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private EmployeeService employeeService;

    private PenalizationPolicyResolutionService resolutionService;
    private PenalisationPolicyManagementService managementService;
    private PenalizationPolicyAllocationService allocationService;

    private final UUID policyLegacyId = UUID.randomUUID();
    private final UUID policyAllocatedId = UUID.randomUUID();
    private final UUID policyDefaultId = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 8, 25);

    @BeforeEach
    void setUp() {
        // ONE real resolution service, shared by both screens — exactly like production DI.
        resolutionService = new PenalizationPolicyResolutionService(
                versionRepository, allocationRepository, penalizationPolicyService, employeeRepository);

        managementService = new PenalisationPolicyManagementService(penalisationPolicyRepository, versionRepository,
                tierRepository, lateHoursTierRepository, allocationRepository, userRepository, auditService,
                auditSnapshot, attendanceProperties, resolutionService);

        allocationService = new PenalizationPolicyAllocationService(allocationRepository, employeeRepository,
                penalisationPolicyRepository, userRepository, auditService, notificationService, auditSnapshot,
                resolutionService, employeeService, attendanceProperties);

        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(penalizationPolicyService.resolveDefaultPolicyId()).thenReturn(policyDefaultId);
        lenient().when(allocationRepository.countByPenalisationPolicyId(any())).thenReturn(0L);
        lenient().when(versionRepository.findByPolicyIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
        lenient().when(employeeService.findCurrentManagersBulk(any())).thenReturn(java.util.Map.of());
    }

    @Test
    void policyList_allocationScreen_and_rawResolution_allAgreeOnTheSameDate() {
        UUID employeeOnLegacyOnly = UUID.randomUUID();
        UUID employeeAllocationOverridesLegacy = UUID.randomUUID();
        UUID employeeOnOrgDefault = UUID.randomUUID();

        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId()).thenReturn(List.<Object[]>of(
                new Object[] { employeeOnLegacyOnly, policyLegacyId },
                // Legacy FK still says Policy Legacy, but a current allocation to Policy Allocated
                // must win — this is exactly the case the old legacy-FK-only Policy List count got
                // wrong (it would have shown this employee against the LEGACY policy).
                new Object[] { employeeAllocationOverridesLegacy, policyLegacyId },
                new Object[] { employeeOnOrgDefault, null }));
        when(allocationRepository.findCurrentAllocationsAt(today)).thenReturn(List.<Object[]>of(
                new Object[] { employeeAllocationOverridesLegacy, policyAllocatedId, LocalDateTime.now() }));

        // Deliberately distinct, ordered createdAt values (not all "now()") — Default Policy must
        // be unambiguously the oldest so both the mocked findFirstByOrderByCreatedAtAsc() and
        // PenalisationPolicyManagementService#list's own in-memory oldest-by-createdAt derivation
        // agree on it, regardless of Stream.min's tie-breaking on equal timestamps.
        List<PenalisationPolicy> allPolicies = List.of(
                PenalisationPolicy.builder().id(policyLegacyId).name("Legacy Policy").createdAt(LocalDateTime.now().minusDays(1)).build(),
                PenalisationPolicy.builder().id(policyAllocatedId).name("Allocated Policy").createdAt(LocalDateTime.now().minusHours(12)).build(),
                PenalisationPolicy.builder().id(policyDefaultId).name("Default Policy").createdAt(LocalDateTime.now().minusDays(2)).build());
        when(penalisationPolicyRepository.findAll()).thenReturn(allPolicies);
        // resolveDefaultPolicyId (via resolveCurrentEmployeeCountsByPolicy) now uses a dedicated
        // ORDER BY createdAt ASC LIMIT 1 query instead of findAll().stream().min(...) — policyDefaultId
        // is this fixture's org default regardless of which of the three rows above is "oldest".
        when(penalisationPolicyRepository.findFirstByOrderByCreatedAtAsc()).thenReturn(
                allPolicies.stream().filter(p -> p.getId().equals(policyDefaultId)).findFirst());

        // ── The one authoritative answer ──
        Map<UUID, Long> authoritative = resolutionService.resolveCurrentEmployeeCountsByPolicy(today);
        assertEquals(1L, authoritative.getOrDefault(policyLegacyId, 0L));
        assertEquals(1L, authoritative.getOrDefault(policyAllocatedId, 0L));
        assertEquals(1L, authoritative.getOrDefault(policyDefaultId, 0L));

        // ── Policy List must report the identical numbers ──
        List<PenalisationPolicySummaryDto> summaries = managementService.list();
        Map<UUID, Long> listCounts = summaries.stream()
                .collect(Collectors.toMap(PenalisationPolicySummaryDto::getId, PenalisationPolicySummaryDto::getEmployeeCount));
        assertEquals(authoritative.getOrDefault(policyLegacyId, 0L), listCounts.get(policyLegacyId),
                "Policy List's Legacy Policy count must match the authoritative resolution");
        assertEquals(authoritative.getOrDefault(policyAllocatedId, 0L), listCounts.get(policyAllocatedId),
                "Policy List's Allocated Policy count must match the authoritative resolution");
        assertEquals(authoritative.getOrDefault(policyDefaultId, 0L), listCounts.get(policyDefaultId),
                "Policy List's Default Policy count must match the authoritative resolution");

        // ── The Allocation screen's "filter by policy" candidate set — the exact set the old
        //    code re-derived independently — must be the identical set the resolution service
        //    and the Policy List already agreed on above. ──
        Map<UUID, UUID> resolvedByEmployee = resolutionService.resolveCurrentPolicyIdsByEmployee(today);
        assertEquals(authoritative.getOrDefault(policyLegacyId, 0L),
                (long) allocationService.matchingEmployeeIds(policyLegacyId, resolvedByEmployee).size());
        assertEquals(authoritative.getOrDefault(policyAllocatedId, 0L),
                (long) allocationService.matchingEmployeeIds(policyAllocatedId, resolvedByEmployee).size());
        assertEquals(authoritative.getOrDefault(policyDefaultId, 0L),
                (long) allocationService.matchingEmployeeIds(policyDefaultId, resolvedByEmployee).size());

        // And the specific employee who moved off the legacy FK via allocation must appear under
        // Allocated, and Allocated only.
        assertEquals(java.util.Set.of(employeeAllocationOverridesLegacy),
                allocationService.matchingEmployeeIds(policyAllocatedId, resolvedByEmployee));
        assertEquals(java.util.Set.of(employeeOnLegacyOnly),
                allocationService.matchingEmployeeIds(policyLegacyId, resolvedByEmployee));
    }
}
