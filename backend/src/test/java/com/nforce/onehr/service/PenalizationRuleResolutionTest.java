package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.config.PenalizationFallbackStrategy;
import com.nforce.onehr.entity.AssignmentRuleAssignment;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.PenalizationPolicyAllocation;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PenalizationPolicyAllocationRepository;
import com.nforce.onehr.repository.PenalizationPolicyVersionRepository;
import com.nforce.onehr.service.assignment.AssignmentDomain;
import com.nforce.onehr.service.assignment.AssignmentRuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * PN-04 acceptance 2: precedence is explicit allocation > auto-assignment rule > legacy FK > org
 * default - identically for the single-employee and the bulk resolution paths.
 */
@ExtendWith(MockitoExtension.class)
class PenalizationRuleResolutionTest {

    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyAllocationRepository allocationRepository;
    @Mock private PenalizationPolicyService penalizationPolicyService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private AssignmentRuleEngine ruleEngine;

    private PenalizationPolicyResolutionService service;
    private final UUID allocated = UUID.randomUUID();
    private final UUID byRule = UUID.randomUUID();
    private final UUID legacy = UUID.randomUUID();
    private final UUID orgDefault = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 11, 25);
    private Employee employee;

    @BeforeEach
    void setUp() {
        service = new PenalizationPolicyResolutionService(versionRepository, allocationRepository, penalizationPolicyService,
                employeeRepository, attendanceProperties, ruleEngine);
        lenient().when(attendanceProperties.getPenalizationFallbackStrategy()).thenReturn(PenalizationFallbackStrategy.DEFAULT_POLICY);
        lenient().when(penalizationPolicyService.resolveActiveDefaultPolicyId()).thenReturn(orgDefault);
        lenient().when(penalizationPolicyService.findActivePolicyIds()).thenReturn(Set.of(allocated, byRule, legacy, orgDefault));
        lenient().when(allocationRepository.findEffectiveAt(any(), any())).thenReturn(List.of());
        lenient().when(allocationRepository.findCurrentAllocationsAt(any())).thenReturn(List.of());
        lenient().when(ruleEngine.resolveAt(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(ruleEngine.allEffectiveAt(any(), any())).thenReturn(Map.of());
        employee = Employee.builder().userId(UUID.randomUUID()).penalisationPolicy(PenalisationPolicy.builder().id(legacy).build()).build();
    }

    private AssignmentRuleAssignment ruleAssignment() {
        return AssignmentRuleAssignment.builder().employeeUserId(employee.getUserId()).targetId(byRule).ruleName("Eng Bangalore")
                .effectiveFrom(date.minusDays(5)).build();
    }

    @Test
    void ruleOutranksTheLegacyFkWhichV95BackfilledForEveryExistingEmployee() {
        when(ruleEngine.resolveAt(AssignmentDomain.PENALIZATION, employee.getUserId(), date)).thenReturn(Optional.of(ruleAssignment()));
        assertEquals(byRule, service.resolveAssignedOrDefaultPolicyId(employee, date));
    }

    @Test
    void explicitAllocationOverridesARuleThatAlsoApplies() {
        when(allocationRepository.findEffectiveAt(employee.getUserId(), date)).thenReturn(List.of(
                PenalizationPolicyAllocation.builder().employeeUserId(employee.getUserId()).penalisationPolicyId(allocated)
                        .effectiveFrom(date).build()));
        assertEquals(allocated, service.resolveAssignedOrDefaultPolicyId(employee, date));
        // An explicit allocation wins before the rules are even consulted.
        org.mockito.Mockito.verify(ruleEngine, org.mockito.Mockito.never()).resolveAt(any(), any(), any());
    }

    @Test
    void noRuleFallsBackToLegacyThenDefault() {
        assertEquals(legacy, service.resolveAssignedOrDefaultPolicyId(employee, date));
        employee.setPenalisationPolicy(null);
        assertEquals(orgDefault, service.resolveAssignedOrDefaultPolicyId(employee, date));
    }

    @Test
    void ruleTargetingAnInactivePolicyFallsThrough() {
        when(ruleEngine.resolveAt(AssignmentDomain.PENALIZATION, employee.getUserId(), date)).thenReturn(Optional.of(ruleAssignment()));
        when(penalizationPolicyService.findActivePolicyIds()).thenReturn(Set.of(legacy, orgDefault)); // byRule deactivated
        assertEquals(legacy, service.resolveAssignedOrDefaultPolicyId(employee, date));
    }

    @Test
    void bulkResolutionFollowsTheSameOrderAsTheSingleEmployeePath() {
        UUID ruleOnly = UUID.randomUUID();
        UUID allocatedToo = UUID.randomUUID();
        when(employeeRepository.findAllEmployeeIdsWithLegacyPolicyId()).thenReturn(List.<Object[]>of(
                new Object[]{employee.getUserId(), legacy},      // has a rule -> rule beats the legacy FK
                new Object[]{ruleOnly, legacy},                  // no rule -> legacy
                new Object[]{allocatedToo, legacy}));            // allocation AND rule -> allocation
        when(ruleEngine.allEffectiveAt(AssignmentDomain.PENALIZATION, date)).thenReturn(Map.of(
                employee.getUserId(), ruleAssignment(),
                allocatedToo, AssignmentRuleAssignment.builder().employeeUserId(allocatedToo).targetId(byRule).ruleName("r").effectiveFrom(date).build()));
        when(allocationRepository.findCurrentAllocationsAt(date)).thenReturn(List.<Object[]>of(
                new Object[]{allocatedToo, allocated, java.time.LocalDateTime.now()}));

        Map<UUID, UUID> resolved = service.resolveCurrentPolicyIdsByEmployee(date);

        assertEquals(byRule, resolved.get(employee.getUserId()));
        assertEquals(legacy, resolved.get(ruleOnly));
        assertEquals(allocated, resolved.get(allocatedToo));
    }
}
