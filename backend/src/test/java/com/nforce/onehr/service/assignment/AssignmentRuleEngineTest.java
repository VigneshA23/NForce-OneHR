package com.nforce.onehr.service.assignment;

import com.nforce.onehr.entity.AssignmentRule;
import com.nforce.onehr.entity.AssignmentRuleAssignment;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Designation;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.repository.AssignmentRuleAssignmentRepository;
import com.nforce.onehr.repository.AssignmentRuleRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.EmployeeShiftAssignmentResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PN-04: the shared rule engine - matching, priority order, and effective-dated re-evaluation. */
@ExtendWith(MockitoExtension.class)
class AssignmentRuleEngineTest {

    private static final String D = AssignmentDomain.PENALIZATION;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    @Mock private AssignmentRuleRepository ruleRepository;
    @Mock private AssignmentRuleAssignmentRepository assignmentRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeShiftAssignmentResolver shiftResolver;
    @Mock private AssignmentTargetResolver targetResolver;

    private AssignmentRuleEngine engine;
    private final UUID deptEng = UUID.randomUUID();
    private final UUID deptHr = UUID.randomUUID();
    private final UUID locBlr = UUID.randomUUID();
    private final UUID policyA = UUID.randomUUID();
    private final UUID policyB = UUID.randomUUID();
    private Employee employee;

    @BeforeEach
    void setUp() {
        lenient().when(targetResolver.domain()).thenReturn(D);
        lenient().when(targetResolver.isUsable(any())).thenReturn(true);
        lenient().when(shiftResolver.resolveIfPresent(any(), any())).thenReturn(Optional.empty());
        engine = new AssignmentRuleEngine(ruleRepository, assignmentRepository, employeeRepository, shiftResolver, List.of(targetResolver));
        Department eng = new Department(); eng.setId(deptEng);
        Location blr = new Location(); blr.setId(locBlr);
        Designation des = new Designation(); des.setGrade("L3");
        employee = Employee.builder().userId(UUID.randomUUID()).department(eng).location(blr).designation(des).employmentType("FULL_TIME").build();
    }

    private AssignmentRule rule(String name, int priority, UUID target) {
        return AssignmentRule.builder().id(UUID.randomUUID()).domain(D).name(name).priority(priority).targetId(target).active(true).build();
    }

    private void rules(AssignmentRule... r) {
        when(ruleRepository.findByDomainAndActiveTrueOrderByPriorityAscCreatedAtAsc(D)).thenReturn(List.of(r));
    }

    // -- matching ------------------------------------------------------------------------------

    @Test
    void ruleMatchesOnlyWhenEveryCriterionItSetsMatches() {
        EmployeeFacts f = engine.factsOf(employee, TODAY);
        AssignmentRule r = rule("Eng Blr", 10, policyA);
        r.setDepartmentId(deptEng); r.setLocationId(locBlr);
        assertTrue(AssignmentRuleEngine.matches(r, f));
        r.setLocationId(UUID.randomUUID());
        assertFalse(AssignmentRuleEngine.matches(r, f), "one wrong criterion is enough to fail the AND");
    }

    @Test
    void employmentTypeAndGradeMatchIgnoringCase() {
        EmployeeFacts f = engine.factsOf(employee, TODAY);
        AssignmentRule r = rule("Full-time L3", 10, policyA);
        r.setEmploymentType("full_time"); r.setGrade(" l3 ");
        assertTrue(AssignmentRuleEngine.matches(r, f));
        r.setGrade("L4");
        assertFalse(AssignmentRuleEngine.matches(r, f));
    }

    @Test
    void lowestPriorityNumberWinsAmongMatchingRules() {
        EmployeeFacts f = engine.factsOf(employee, TODAY);
        AssignmentRule broad = rule("Everyone in Engineering", 20, policyB); broad.setDepartmentId(deptEng);
        AssignmentRule specific = rule("Engineering Bangalore", 10, policyA); specific.setDepartmentId(deptEng); specific.setLocationId(locBlr);
        AssignmentRule other = rule("HR only", 5, policyB); other.setDepartmentId(deptHr);
        // The caller passes them already sorted by priority (the repository does that).
        Optional<AssignmentRule> hit = AssignmentRuleEngine.firstMatch(List.of(other, specific, broad), f);
        assertEquals("Engineering Bangalore", hit.orElseThrow().getName());
    }

    // -- effective-dated re-evaluation ---------------------------------------------------------

    @Test
    void firstEvaluationOpensARecordEffectiveToday() {
        AssignmentRule r = rule("Eng", 10, policyA); r.setDepartmentId(deptEng);
        rules(r);
        when(assignmentRepository.findFirstByDomainAndEmployeeUserIdAndEffectiveToIsNullOrderByEffectiveFromDesc(D, employee.getUserId()))
                .thenReturn(Optional.empty());

        assertTrue(engine.reevaluate(D, employee, TODAY));

        ArgumentCaptor<AssignmentRuleAssignment> saved = ArgumentCaptor.forClass(AssignmentRuleAssignment.class);
        verify(assignmentRepository).save(saved.capture());
        assertEquals(policyA, saved.getValue().getTargetId());
        assertEquals(TODAY, saved.getValue().getEffectiveFrom());
        assertNull(saved.getValue().getEffectiveTo());
        assertEquals("Eng", saved.getValue().getRuleName());
    }

    @Test
    void unchangedOutcomeWritesNothing() {
        AssignmentRule r = rule("Eng", 10, policyA); r.setDepartmentId(deptEng);
        rules(r);
        AssignmentRuleAssignment open = AssignmentRuleAssignment.builder().domain(D).employeeUserId(employee.getUserId())
                .targetId(policyA).ruleId(r.getId()).ruleName("Eng").effectiveFrom(TODAY.minusDays(30)).build();
        when(assignmentRepository.findFirstByDomainAndEmployeeUserIdAndEffectiveToIsNullOrderByEffectiveFromDesc(D, employee.getUserId()))
                .thenReturn(Optional.of(open));

        assertFalse(engine.reevaluate(D, employee, TODAY));
        verify(assignmentRepository, never()).save(any());
        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void attributeChangeClosesTheOldRecordYesterdayAndOpensANewOneToday() {
        // The employee moved from Engineering to HR: the Eng rule no longer matches, the HR rule does.
        employee.getDepartment().setId(deptHr);
        AssignmentRule engRule = rule("Eng", 10, policyA); engRule.setDepartmentId(deptEng);
        AssignmentRule hrRule = rule("HR", 20, policyB); hrRule.setDepartmentId(deptHr);
        rules(engRule, hrRule);
        AssignmentRuleAssignment open = AssignmentRuleAssignment.builder().domain(D).employeeUserId(employee.getUserId())
                .targetId(policyA).ruleId(engRule.getId()).ruleName("Eng").effectiveFrom(TODAY.minusDays(60)).build();
        when(assignmentRepository.findFirstByDomainAndEmployeeUserIdAndEffectiveToIsNullOrderByEffectiveFromDesc(D, employee.getUserId()))
                .thenReturn(Optional.of(open));

        assertTrue(engine.reevaluate(D, employee, TODAY));

        assertEquals(TODAY.minusDays(1), open.getEffectiveTo(), "history is closed, not rewritten");
        ArgumentCaptor<AssignmentRuleAssignment> saved = ArgumentCaptor.forClass(AssignmentRuleAssignment.class);
        verify(assignmentRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        AssignmentRuleAssignment created = saved.getAllValues().get(1);
        assertEquals(policyB, created.getTargetId());
        assertEquals(TODAY, created.getEffectiveFrom());
    }

    @Test
    void noLongerMatchingAnyRuleClosesTheRecord() {
        rules(); // no active rules at all now
        AssignmentRuleAssignment open = AssignmentRuleAssignment.builder().domain(D).employeeUserId(employee.getUserId())
                .targetId(policyA).ruleName("Eng").effectiveFrom(TODAY.minusDays(10)).build();
        when(assignmentRepository.findFirstByDomainAndEmployeeUserIdAndEffectiveToIsNullOrderByEffectiveFromDesc(D, employee.getUserId()))
                .thenReturn(Optional.of(open));

        assertTrue(engine.reevaluate(D, employee, TODAY));
        assertEquals(TODAY.minusDays(1), open.getEffectiveTo());
    }

    @Test
    void aRecordThatBeganTodayIsReplacedNotClosedToAnInvertedRange() {
        AssignmentRule r = rule("New", 10, policyB); r.setDepartmentId(deptEng);
        rules(r);
        AssignmentRuleAssignment open = AssignmentRuleAssignment.builder().domain(D).employeeUserId(employee.getUserId())
                .targetId(policyA).ruleId(UUID.randomUUID()).ruleName("Old").effectiveFrom(TODAY).build();
        when(assignmentRepository.findFirstByDomainAndEmployeeUserIdAndEffectiveToIsNullOrderByEffectiveFromDesc(D, employee.getUserId()))
                .thenReturn(Optional.of(open));

        assertTrue(engine.reevaluate(D, employee, TODAY));
        verify(assignmentRepository).delete(open);
    }

    @Test
    void ruleTargetingAnInactivePolicyIsSkipped() {
        AssignmentRule dead = rule("Dead policy", 10, policyA); dead.setDepartmentId(deptEng);
        AssignmentRule live = rule("Live policy", 20, policyB); live.setDepartmentId(deptEng);
        rules(dead, live);
        when(targetResolver.isUsable(policyA)).thenReturn(false);
        when(assignmentRepository.findFirstByDomainAndEmployeeUserIdAndEffectiveToIsNullOrderByEffectiveFromDesc(D, employee.getUserId()))
                .thenReturn(Optional.empty());

        engine.reevaluate(D, employee, TODAY);

        ArgumentCaptor<AssignmentRuleAssignment> saved = ArgumentCaptor.forClass(AssignmentRuleAssignment.class);
        verify(assignmentRepository).save(saved.capture());
        assertEquals(policyB, saved.getValue().getTargetId());
    }

    @Test
    void resolveAtReadsTheRecordedHistoryForThatDate() {
        AssignmentRuleAssignment past = AssignmentRuleAssignment.builder().targetId(policyA).ruleName("Old").build();
        when(assignmentRepository.findEffectiveAt(eq(D), eq(employee.getUserId()), eq(TODAY.minusDays(40)))).thenReturn(List.of(past));
        when(assignmentRepository.findEffectiveAt(eq(D), eq(employee.getUserId()), eq(TODAY.minusDays(100)))).thenReturn(List.of());

        assertEquals(policyA, engine.resolveAt(D, employee.getUserId(), TODAY.minusDays(40)).orElseThrow().getTargetId());
        assertTrue(engine.resolveAt(D, employee.getUserId(), TODAY.minusDays(100)).isEmpty());
    }
}
