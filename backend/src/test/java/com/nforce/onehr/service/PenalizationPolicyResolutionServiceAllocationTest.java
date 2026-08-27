package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.PenalizationPolicyAllocation;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PenalizationPolicyAllocationRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Proves the Penalization Policy Allocation table actually drives resolution — not just stored,
 * genuinely consumed. Covers the exact "today Policy A, tomorrow Policy B" scenario from the
 * Allocation spec, and that an allocation row always outranks the legacy
 * {@code employee.penalisationPolicy} FK when both exist for the same employee.
 */
@ExtendWith(MockitoExtension.class)
class PenalizationPolicyResolutionServiceAllocationTest {

    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyAllocationRepository allocationRepository;
    @Mock private PenalizationPolicyService penalizationPolicyService;
    @Mock private EmployeeRepository employeeRepository;

    private PenalizationPolicyResolutionService resolutionService;

    private final UUID legacyPolicyId = UUID.randomUUID();
    private final UUID allocationPolicyAId = UUID.randomUUID();
    private final UUID allocationPolicyBId = UUID.randomUUID();
    private final UUID defaultPolicyId = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 8, 25);
    private final LocalDate tomorrow = today.plusDays(1);

    @BeforeEach
    void setUp() {
        resolutionService = new PenalizationPolicyResolutionService(versionRepository, allocationRepository, penalizationPolicyService, employeeRepository);
        lenient().when(penalizationPolicyService.resolveDefaultPolicyId()).thenReturn(defaultPolicyId);
        lenient().when(allocationRepository.findCurrentAllocationsAt(any())).thenReturn(List.of());
        lenient().when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId()).thenReturn(List.of());
    }

    private Employee employeeWithLegacyPolicy(UUID legacyId) {
        PenalisationPolicy legacy = legacyId == null ? null : PenalisationPolicy.builder().id(legacyId).build();
        return Employee.builder().userId(UUID.randomUUID()).penalisationPolicy(legacy).build();
    }

    @Test
    void todayPolicyA_tomorrowPolicyB_sameEmployee_differentDates() {
        Employee employee = employeeWithLegacyPolicy(null);
        PenalizationPolicyAllocation rowA = PenalizationPolicyAllocation.builder()
                .employeeUserId(employee.getUserId()).penalisationPolicyId(allocationPolicyAId)
                .effectiveFrom(today).effectiveTo(today).build();
        PenalizationPolicyAllocation rowB = PenalizationPolicyAllocation.builder()
                .employeeUserId(employee.getUserId()).penalisationPolicyId(allocationPolicyBId)
                .effectiveFrom(tomorrow).effectiveTo(null).build();
        when(allocationRepository.findEffectiveAt(employee.getUserId(), today)).thenReturn(List.of(rowA));
        when(allocationRepository.findEffectiveAt(employee.getUserId(), tomorrow)).thenReturn(List.of(rowB));

        assertEquals(allocationPolicyAId, resolutionService.resolveAssignedOrDefaultPolicyId(employee, today));
        assertEquals(allocationPolicyBId, resolutionService.resolveAssignedOrDefaultPolicyId(employee, tomorrow));
    }

    @Test
    void allocationRow_outranksLegacyFkForTheSameEmployee() {
        Employee employee = employeeWithLegacyPolicy(legacyPolicyId);
        PenalizationPolicyAllocation currentAllocation = PenalizationPolicyAllocation.builder()
                .employeeUserId(employee.getUserId()).penalisationPolicyId(allocationPolicyAId)
                .effectiveFrom(today).effectiveTo(null).build();
        when(allocationRepository.findEffectiveAt(employee.getUserId(), today)).thenReturn(List.of(currentAllocation));

        assertEquals(allocationPolicyAId, resolutionService.resolveAssignedOrDefaultPolicyId(employee, today));
    }

    @Test
    void noAllocationRow_fallsBackToLegacyFk() {
        Employee employee = employeeWithLegacyPolicy(legacyPolicyId);
        when(allocationRepository.findEffectiveAt(employee.getUserId(), today)).thenReturn(List.of());

        assertEquals(legacyPolicyId, resolutionService.resolveAssignedOrDefaultPolicyId(employee, today));
    }

    @Test
    void noAllocationAndNoLegacyFk_fallsBackToOrgDefault() {
        Employee employee = employeeWithLegacyPolicy(null);
        when(allocationRepository.findEffectiveAt(employee.getUserId(), today)).thenReturn(List.of());

        assertEquals(defaultPolicyId, resolutionService.resolveAssignedOrDefaultPolicyId(employee, today));
    }

    @Test
    void allocationRowExpired_beforeItStarts_stillFallsBackToLegacyFk() {
        Employee employee = employeeWithLegacyPolicy(legacyPolicyId);
        // A future-dated row exists, but not effective yet on `today` — findEffectiveAt correctly
        // returns nothing for `today`, proving the resolution never looks at rows outside the
        // requested date's own effective window.
        when(allocationRepository.findEffectiveAt(employee.getUserId(), today)).thenReturn(List.of());

        assertEquals(legacyPolicyId, resolutionService.resolveAssignedOrDefaultPolicyId(employee, today));
    }

    // ── Authoritative employee count — resolveCurrentPolicyIdsByEmployee / resolveCurrentEmployeeCount ──
    // The single source both the Policy List's "Employee Count" column and the Penalization
    // Policy Allocation screen must read from, so the two screens can never disagree.

    private Object[] employeeRow(UUID employeeId, UUID legacyPolicyIdOrNull) {
        return new Object[] { employeeId, legacyPolicyIdOrNull };
    }

    private Object[] allocationRow(UUID employeeId, UUID policyId, LocalDateTime createdAt) {
        return new Object[] { employeeId, policyId, createdAt };
    }

    @Test
    void count_legacyFkOnly() {
        UUID employeeId = UUID.randomUUID();
        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId())
                .thenReturn(List.<Object[]>of(employeeRow(employeeId, legacyPolicyId)));
        when(allocationRepository.findCurrentAllocationsAt(today)).thenReturn(List.of());

        Map<UUID, Long> counts = resolutionService.resolveCurrentEmployeeCountsByPolicy(today);

        assertEquals(1L, counts.getOrDefault(legacyPolicyId, 0L));
        assertEquals(1L, resolutionService.resolveCurrentEmployeeCount(legacyPolicyId, today));
    }

    @Test
    void count_allocationOnly() {
        UUID employeeId = UUID.randomUUID();
        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId())
                .thenReturn(List.<Object[]>of(employeeRow(employeeId, null)));
        when(allocationRepository.findCurrentAllocationsAt(today))
                .thenReturn(List.<Object[]>of(allocationRow(employeeId, allocationPolicyAId, LocalDateTime.now())));

        assertEquals(1L, resolutionService.resolveCurrentEmployeeCount(allocationPolicyAId, today));
    }

    @Test
    void count_allocationOverridesLegacyFk() {
        UUID employeeId = UUID.randomUUID();
        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId())
                .thenReturn(List.<Object[]>of(employeeRow(employeeId, legacyPolicyId)));
        when(allocationRepository.findCurrentAllocationsAt(today))
                .thenReturn(List.<Object[]>of(allocationRow(employeeId, allocationPolicyBId, LocalDateTime.now())));

        Map<UUID, Long> counts = resolutionService.resolveCurrentEmployeeCountsByPolicy(today);

        assertEquals(1L, counts.getOrDefault(allocationPolicyBId, 0L), "allocation wins over the legacy FK");
        assertEquals(0L, counts.getOrDefault(legacyPolicyId, 0L), "legacy FK must not also be counted once an allocation exists");
    }

    @Test
    void count_futureAllocationIsNotCountedToday() {
        UUID employeeId = UUID.randomUUID();
        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId())
                .thenReturn(List.<Object[]>of(employeeRow(employeeId, legacyPolicyId)));
        // A row exists for tomorrow, but findCurrentAllocationsAt(today) — the real repository
        // query — correctly excludes it since its effectiveFrom is after `today`.
        when(allocationRepository.findCurrentAllocationsAt(today)).thenReturn(List.of());

        Map<UUID, Long> counts = resolutionService.resolveCurrentEmployeeCountsByPolicy(today);

        assertEquals(1L, counts.getOrDefault(legacyPolicyId, 0L), "today the employee still counts against the legacy policy");
        assertEquals(0L, counts.getOrDefault(allocationPolicyBId, 0L), "the future allocation must not be counted today");
    }

    @Test
    void count_futureAllocationBecomesCurrentOnItsEffectiveDate() {
        UUID employeeId = UUID.randomUUID();
        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId())
                .thenReturn(List.<Object[]>of(employeeRow(employeeId, legacyPolicyId)));
        when(allocationRepository.findCurrentAllocationsAt(today)).thenReturn(List.of());
        when(allocationRepository.findCurrentAllocationsAt(tomorrow))
                .thenReturn(List.<Object[]>of(allocationRow(employeeId, allocationPolicyBId, LocalDateTime.now())));

        assertEquals(1L, resolutionService.resolveCurrentEmployeeCount(legacyPolicyId, today));
        assertEquals(0L, resolutionService.resolveCurrentEmployeeCount(allocationPolicyBId, today));

        assertEquals(0L, resolutionService.resolveCurrentEmployeeCount(legacyPolicyId, tomorrow),
                "the employee has moved off the legacy policy as of tomorrow");
        assertEquals(1L, resolutionService.resolveCurrentEmployeeCount(allocationPolicyBId, tomorrow));
    }

    @Test
    void count_expiredAllocationFallsBackToLegacyFk() {
        UUID employeeId = UUID.randomUUID();
        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId())
                .thenReturn(List.<Object[]>of(employeeRow(employeeId, legacyPolicyId)));
        // The allocation to Policy A expired before `today` — the real query never returns it for
        // findCurrentAllocationsAt(today), so the employee falls through to the legacy FK.
        when(allocationRepository.findCurrentAllocationsAt(today)).thenReturn(List.of());

        Map<UUID, Long> counts = resolutionService.resolveCurrentEmployeeCountsByPolicy(today);

        assertEquals(1L, counts.getOrDefault(legacyPolicyId, 0L));
        assertEquals(0L, counts.getOrDefault(allocationPolicyAId, 0L));
    }

    @Test
    void count_organizationDefaultResolution() {
        UUID employeeId = UUID.randomUUID();
        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId())
                .thenReturn(List.<Object[]>of(employeeRow(employeeId, null)));
        when(allocationRepository.findCurrentAllocationsAt(today)).thenReturn(List.of());

        assertEquals(1L, resolutionService.resolveCurrentEmployeeCount(defaultPolicyId, today));
    }

    @Test
    void count_multipleEmployees_splitAcrossAllocationLegacyAndDefault() {
        UUID allocatedEmployee = UUID.randomUUID();
        UUID legacyEmployee = UUID.randomUUID();
        UUID defaultEmployee = UUID.randomUUID();
        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId()).thenReturn(List.of(
                employeeRow(allocatedEmployee, legacyPolicyId), // has both — allocation still wins
                employeeRow(legacyEmployee, legacyPolicyId),
                employeeRow(defaultEmployee, null)));
        when(allocationRepository.findCurrentAllocationsAt(today))
                .thenReturn(List.<Object[]>of(allocationRow(allocatedEmployee, allocationPolicyAId, LocalDateTime.now())));

        Map<UUID, Long> counts = resolutionService.resolveCurrentEmployeeCountsByPolicy(today);

        assertEquals(1L, counts.getOrDefault(allocationPolicyAId, 0L));
        assertEquals(1L, counts.getOrDefault(legacyPolicyId, 0L));
        assertEquals(1L, counts.getOrDefault(defaultPolicyId, 0L));
    }

    @Test
    void count_noPenalisationPolicyExistsAtAll_neverThrows() {
        UUID employeeId = UUID.randomUUID();
        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId())
                .thenReturn(List.<Object[]>of(employeeRow(employeeId, null)));
        when(allocationRepository.findCurrentAllocationsAt(today)).thenReturn(List.of());
        when(penalizationPolicyService.resolveDefaultPolicyId()).thenThrow(new IllegalStateException("no policy"));

        Map<UUID, Long> counts = resolutionService.resolveCurrentEmployeeCountsByPolicy(today);

        assertEquals(0, counts.size(), "an employee who resolves to no policy at all contributes to no count");
        assertNull(resolutionService.resolveAssignedOrDefaultPolicyId(employeeWithLegacyPolicy(null), today));
    }
}
