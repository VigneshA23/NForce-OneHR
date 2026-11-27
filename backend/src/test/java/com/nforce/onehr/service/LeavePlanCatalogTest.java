package com.nforce.onehr.service;

import com.nforce.onehr.entity.AssignmentRuleAssignment;
import com.nforce.onehr.entity.LeavePlan;
import com.nforce.onehr.entity.LeavePlanAllocation;
import com.nforce.onehr.entity.LeavePlanVersion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** LV-01: which plan an employee is on for a date, and which version's rules apply. */
class LeavePlanCatalogTest {

    private final UUID emp = UUID.randomUUID();
    private final UUID paidType = UUID.randomUUID();
    private final LeavePlan fullTime = LeavePlan.builder().id(UUID.randomUUID()).name("Full-time India").orgDefault(true).build();
    private final LeavePlan interns = LeavePlan.builder().id(UUID.randomUUID()).name("Interns").build();
    private final LeavePlan contractors = LeavePlan.builder().id(UUID.randomUUID()).name("Contractors").build();

    private final Map<UUID, List<LeavePlanAllocation>> allocations = new HashMap<>();
    private final Map<UUID, List<AssignmentRuleAssignment>> byRule = new HashMap<>();

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private LeavePlanCatalog catalog() {
        UUID v1 = UUID.randomUUID(), v2 = UUID.randomUUID(), vi = UUID.randomUUID(), vc = UUID.randomUUID();
        Map<UUID, List<LeavePlanVersion>> versions = Map.of(
                fullTime.getId(), List.of(
                        // deliberately out of order: the catalog sorts
                        LeavePlanVersion.builder().id(v2).planId(fullTime.getId()).version(2).effectiveFrom(d("2027-01-01")).build(),
                        LeavePlanVersion.builder().id(v1).planId(fullTime.getId()).version(1).effectiveFrom(d("2026-01-01"))
                                .effectiveTo(d("2026-12-31")).build()),
                interns.getId(), List.of(LeavePlanVersion.builder().id(vi).planId(interns.getId()).version(1).effectiveFrom(d("2026-01-01")).build()),
                contractors.getId(), List.of(LeavePlanVersion.builder().id(vc).planId(contractors.getId()).version(1).effectiveFrom(d("2026-01-01")).build()));
        Map<UUID, Map<UUID, LeavePlanCatalog.TypeRule>> rules = Map.of(
                v1, Map.of(paidType, rule(fullTime, v1, "15")),
                v2, Map.of(paidType, rule(fullTime, v2, "18")),
                vi, Map.of(), // interns get no paid leave
                vc, Map.of(paidType, rule(contractors, vc, "6")));
        return new LeavePlanCatalog(Map.of(fullTime.getId(), fullTime, interns.getId(), interns, contractors.getId(), contractors),
                versions, rules, allocations, byRule, null);
    }

    private LeavePlanCatalog.TypeRule rule(LeavePlan plan, UUID version, String days) {
        return new LeavePlanCatalog.TypeRule(UUID.randomUUID(), plan.getId(), version, paidType, new BigDecimal(days), "YEARLY",
                false, null, null, "CARRY_FORWARD_FIRST", LeaveEligibilityService.Rules.EVERYONE);
    }

    private void allocate(LeavePlan plan, String from, String to) {
        allocations.computeIfAbsent(emp, k -> new java.util.ArrayList<>()).add(LeavePlanAllocation.builder()
                .employeeUserId(emp).planId(plan.getId()).effectiveFrom(d(from)).effectiveTo(to == null ? null : d(to)).build());
    }

    private void byRule(LeavePlan plan, String from) {
        byRule.computeIfAbsent(emp, k -> new java.util.ArrayList<>()).add(AssignmentRuleAssignment.builder()
                .employeeUserId(emp).targetId(plan.getId()).ruleName("All contractors").effectiveFrom(d(from)).build());
    }

    @Test
    void withNothingAssigned_everyoneIsOnTheOrgDefault() {
        LeavePlanCatalog.PlanResolution r = catalog().planOf(emp, d("2026-06-01"));
        assertEquals(fullTime.getId(), r.planId());
        assertEquals(LeavePlanCatalog.SOURCE_DEFAULT, r.source());
    }

    @Test
    void anAllocationOutranksARule_whichOutranksTheDefault_eachOnlyWithinItsDates() {
        byRule(contractors, "2026-03-01");
        allocate(interns, "2026-05-01", "2026-08-31");
        LeavePlanCatalog c = catalog();
        assertEquals(LeavePlanCatalog.SOURCE_DEFAULT, c.planOf(emp, d("2026-02-28")).source());
        LeavePlanCatalog.PlanResolution rule = c.planOf(emp, d("2026-04-15"));
        assertEquals(contractors.getId(), rule.planId());
        assertEquals("All contractors", rule.ruleName());
        assertEquals(interns.getId(), c.planOf(emp, d("2026-06-01")).planId());
        assertEquals(LeavePlanCatalog.SOURCE_ALLOCATION, c.planOf(emp, d("2026-06-01")).source());
        assertEquals(contractors.getId(), c.planOf(emp, d("2026-09-01")).planId()); // allocation ended
    }

    @Test
    void anInactivePlan_isSkipped_atEveryStep() {
        allocate(interns, "2026-01-01", null);
        byRule(contractors, "2026-01-01");
        interns.setStatus(LeavePlan.INACTIVE);
        assertEquals(contractors.getId(), catalog().planOf(emp, d("2026-06-01")).planId());
        contractors.setStatus(LeavePlan.INACTIVE);
        assertEquals(fullTime.getId(), catalog().planOf(emp, d("2026-06-01")).planId());
        fullTime.setStatus(LeavePlan.INACTIVE);
        assertNull(catalog().planOf(emp, d("2026-06-01")));
        assertTrue(catalog().ruleFor(emp, paidType, d("2026-06-01")).isEmpty());
    }

    @Test
    void theVersionInEffectOnTheDate_givesTheRules_andEarlierDatesUseTheFirstVersion() {
        LeavePlanCatalog c = catalog();
        assertEquals(0, new BigDecimal("15").compareTo(c.ruleFor(emp, paidType, d("2026-12-31")).orElseThrow().days()));
        assertEquals(0, new BigDecimal("18").compareTo(c.ruleFor(emp, paidType, d("2027-01-01")).orElseThrow().days()));
        assertEquals(0, new BigDecimal("15").compareTo(c.ruleFor(emp, paidType, d("2019-05-01")).orElseThrow().days()));
    }

    @Test
    void aTypeThePlanDoesNotInclude_hasNoRule() {
        allocate(interns, "2026-01-01", null);
        assertTrue(catalog().ruleFor(emp, paidType, d("2026-06-01")).isEmpty());
    }

    @Test
    void askingForAnEmployeeWhoseAssignmentsWereNotLoaded_fails() {
        LeavePlanCatalog c = new LeavePlanCatalog(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), java.util.Set.of(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> c.planOf(emp, d("2026-01-01")));
    }
}
