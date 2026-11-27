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
