package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeaveConfigDtos.EligibilityConfig;
import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeavePlanTypeEligibility;
import com.nforce.onehr.entity.LeavePlanTypeOverride;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * "Applies to" (V314, per leave plan since LV-01): a type is available only if the employee's plan
 * includes it; then rules OR'ed within a kind and AND'ed across kinds, include/exclude overrides, null gender.
 */
@ExtendWith(MockitoExtension.class)
class LeaveEligibilityServiceTest {

    @Mock private EmployeeRepository employeeRepo;

    private LeaveEligibilityService service;
    private final TestLeavePlans plans = new TestLeavePlans();
    private final LeaveType maternity = LeaveType.builder().id(UUID.randomUUID()).code("MATERNITY").name("Maternity Leave")
            .entitlementBasis(LeaveType.BASIS_PER_OCCURRENCE).build();
    private final List<LeavePlanTypeEligibility> rules = new ArrayList<>();
    private final List<LeavePlanTypeOverride> overrides = new ArrayList<>();
    private final UUID hyderabad = UUID.randomUUID();
    private final UUID sales = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LeaveEligibilityService(plans.service(), employeeRepo, new AttendanceProperties());
        plans.with(maternity, "182");
    }

    private void rule(String type, String value) {
        rules.add(LeavePlanTypeEligibility.builder().ruleType(type).value(value).build());
        applyRules();
    }

    private void override(UUID employee, String mode) {
        overrides.add(LeavePlanTypeOverride.builder().employeeUserId(employee).mode(mode).build());
        applyRules();
    }

    private void applyRules() {
        plans.eligibility(maternity, LeavePlanRuleService.eligibilityRules(rules, overrides));
    }

    private UUID employee(String gender, UUID department, UUID location, String employmentType) {
        UUID id = UUID.randomUUID();
        lenient().when(employeeRepo.findLeaveProfile(id)).thenReturn(Optional.of(new LeaveEmployeeProfile(id, gender, department,
                location, employmentType, LocalDate.of(2020, 1, 1), null, true, null)));
        return id;
    }

    @Test
    void noRules_appliesToEveryone_evenUsersWithNoEmployeeRecord() {
        assertTrue(service.isEligible(employee(null, null, null, "INTERN"), maternity));
        assertTrue(service.isEligible(UUID.randomUUID(), maternity));
    }

    @Test
    void aTypeNotInTheEmployeesPlan_isNotAvailable() {
        plans.without(maternity);
        assertFalse(service.isEligible(employee("Female", null, null, "FULL_TIME"), maternity));
    }

    @Test
    void genderRule_matchesCaseInsensitively_andAMissingGenderNeverMatches() {
        rule(LeavePlanTypeEligibility.GENDER, "Female");
        assertTrue(service.isEligible(employee("female", null, null, "FULL_TIME"), maternity));
        assertFalse(service.isEligible(employee("Male", null, null, "FULL_TIME"), maternity));
        assertFalse(service.isEligible(employee(null, null, null, "FULL_TIME"), maternity));
        assertFalse(service.isEligible(employee("  ", null, null, "FULL_TIME"), maternity));
    }

    @Test
    void valuesOfOneKindAreOred_differentKindsAreAnded() {
        rule(LeavePlanTypeEligibility.LOCATION, hyderabad.toString());
        rule(LeavePlanTypeEligibility.EMPLOYMENT_TYPE, "FULL_TIME");
        rule(LeavePlanTypeEligibility.EMPLOYMENT_TYPE, "PART_TIME");
        assertTrue(service.isEligible(employee("Male", sales, hyderabad, "PART_TIME"), maternity));
        assertFalse(service.isEligible(employee("Male", sales, hyderabad, "INTERN"), maternity));
        assertFalse(service.isEligible(employee("Male", sales, UUID.randomUUID(), "FULL_TIME"), maternity));
        assertFalse(service.isEligible(employee("Male", sales, null, "FULL_TIME"), maternity));
    }

    @Test
    void includeOverridesTheRules_excludeOverridesEverything() {
        rule(LeavePlanTypeEligibility.GENDER, "Female");
        UUID man = employee("Male", null, null, "FULL_TIME");
        UUID woman = employee("Female", null, null, "FULL_TIME");
        override(man, LeavePlanTypeOverride.INCLUDE);
        override(woman, LeavePlanTypeOverride.EXCLUDE);
        assertTrue(service.isEligible(man, maternity));
        assertFalse(service.isEligible(woman, maternity));
    }

    @Test
    void eligibleTypes_keepsThePlansTypesTheEmployeeMatches() {
        rule(LeavePlanTypeEligibility.GENDER, "Female");
        LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave").build();
        LeaveType notInPlan = LeaveType.builder().id(UUID.randomUUID()).code("STUDY").name("Study Leave").build();
        plans.with(paid, "15");
        UUID man = employee("Male", null, null, "FULL_TIME");
        assertEquals(List.of(paid), service.eligibleTypes(man, List.of(paid, maternity, notInPlan)));
    }

    @Test
    void preview_countsCurrentEmployeesOnly() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        when(employeeRepo.findLeaveProfiles()).thenReturn(List.of(
                new LeaveEmployeeProfile(a, "Female", null, null, "FULL_TIME", null, null, true, null),
                new LeaveEmployeeProfile(b, "Male", null, null, "FULL_TIME", null, null, true, null),
                new LeaveEmployeeProfile(c, "Female", null, null, "FULL_TIME", null, null, false, null)));
        int[] counts = service.preview(new EligibilityConfig(List.of("Female"), null, null, null, null, null));
        assertArrayEquals(new int[] {1, 2}, counts);
    }

    // ── LV-06: marital status, grade, tenure on the start date, probation ──

    private UUID employee(String marital, String grade, LocalDate joining, LocalDate probationEnd, LocalDate confirmed) {
        UUID id = UUID.randomUUID();
        lenient().when(employeeRepo.findLeaveProfile(id)).thenReturn(Optional.of(new LeaveEmployeeProfile(id, "Female", null, null,
                "FULL_TIME", joining, null, true, null, marital, grade, probationEnd, confirmed)));
        return id;
    }

    private void dated(Integer minTenureMonths, boolean allowedDuringProbation) {
        plans.eligibility(maternity, LeavePlanRuleService.eligibilityRules(rules, overrides, minTenureMonths, allowedDuringProbation));
    }

    @Test
    void maritalStatusAndGradeRules_matchCaseInsensitively() {
        rule(LeavePlanTypeEligibility.MARITAL_STATUS, "Married");
        rule(LeavePlanTypeEligibility.GRADE, "G3");
        assertTrue(service.isEligible(employee("married", "g3", null, null, null), maternity));
        assertFalse(service.isEligible(employee("Single", "G3", null, null, null), maternity));
        assertFalse(service.isEligible(employee("Married", null, null, null, null), maternity));
    }

    @Test
    void tenure_isCheckedOnTheLeaveStartDate_withTheDateItIsReached() {
        dated(6, true);
        UUID joiner = employee(null, null, LocalDate.of(2026, 3, 15), null, null);
        LeaveEligibilityService.Availability early = service.availability(joiner, maternity, LocalDate.of(2026, 9, 14));
        assertFalse(early.available());
        assertTrue(early.forEmployee(), "still listed for the employee");
        assertEquals(LocalDate.of(2026, 9, 15), early.availableFrom());
        assertEquals("Maternity Leave needs 6 months of service: you can take it from 15 Sep 2026.", early.reason());
        assertTrue(service.isEligible(joiner, maternity, LocalDate.of(2026, 9, 15)));
        // No joining date on file: held back, with no date to offer.
        UUID unknown = employee(null, null, null, null, null);
        assertEquals("Maternity Leave needs 6 months of service (your joining date isn't on file).",
                service.availability(unknown, maternity, LocalDate.of(2026, 9, 15)).reason());
        // The "who" check alone ignores tenure (balances, penalties).
        assertTrue(service.isEligible(joiner, maternity));
    }

    @Test
    void probation_endsAtConfirmation_orAfterTheProbationEndDate() {
        dated(null, false);
        UUID confirmedLater = employee(null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), LocalDate.of(2026, 8, 1));
        LeaveEligibilityService.Availability a = service.availability(confirmedLater, maternity, LocalDate.of(2026, 7, 15));
        assertFalse(a.available());
        assertEquals("Maternity Leave isn't available during probation: you can take it from 1 Aug 2026.", a.reason());
        assertTrue(service.isEligible(confirmedLater, maternity, LocalDate.of(2026, 8, 1)));
        UUID noConfirmation = employee(null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), null);
        assertFalse(service.isEligible(noConfirmation, maternity, LocalDate.of(2026, 6, 30)));
        assertTrue(service.isEligible(noConfirmation, maternity, LocalDate.of(2026, 7, 1)));
        // No probation dates at all = not on probation.
        assertTrue(service.isEligible(employee(null, null, LocalDate.of(2026, 1, 1), null, null), maternity, LocalDate.of(2026, 2, 1)));
    }

    @Test
    void aHandPickedInclude_bypassesTenureAndProbation() {
        UUID onProbation = employee(null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null);
        overrides.add(LeavePlanTypeOverride.builder().employeeUserId(onProbation).mode(LeavePlanTypeOverride.INCLUDE).build());
        dated(12, false);
        assertTrue(service.isEligible(onProbation, maternity, LocalDate.of(2026, 3, 1)));
    }

    @Test
    void typesForEmployee_listsHeldBackTypes_butNotTypesForOthers() {
        rule(LeavePlanTypeEligibility.MARITAL_STATUS, "Married");
        dated(600, true); // held back by tenure for anyone
        UUID married = employee("Married", null, LocalDate.of(2026, 1, 1), null, null);
        UUID single = employee("Single", null, LocalDate.of(2026, 1, 1), null, null);
        var forMarried = service.typesForEmployee(married, List.of(maternity));
        assertEquals(1, forMarried.size());
        assertFalse(forMarried.get(maternity).available());
        assertTrue(service.typesForEmployee(single, List.of(maternity)).isEmpty());
    }

    @Test
    void clean_dropsBlanksAndDuplicates_andExcludeWinsOverInclude() {
        UUID both = UUID.randomUUID();
        EligibilityConfig c = LeaveEligibilityService.clean(new EligibilityConfig(
                List.of("Female", " female ", ""), null, null, List.of("FULL_TIME"), List.of(both), List.of(both)));
        assertEquals(List.of("Female"), c.genders());
        assertEquals(List.of(), c.includeEmployeeIds());
        assertEquals(List.of(both), c.excludeEmployeeIds());
    }
}
