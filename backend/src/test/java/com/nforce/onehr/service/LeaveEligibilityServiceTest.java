package com.nforce.onehr.service;

import com.nforce.onehr.dto.LeaveConfigDtos.EligibilityConfig;
import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.LeaveTypeEligibilityRule;
import com.nforce.onehr.entity.LeaveTypeEmployeeOverride;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveTypeEligibilityRuleRepository;
import com.nforce.onehr.repository.LeaveTypeEmployeeOverrideRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** V314 "applies to": rules OR'ed within a kind and AND'ed across kinds, include/exclude overrides, null gender. */
@ExtendWith(MockitoExtension.class)
class LeaveEligibilityServiceTest {

    @Mock private LeaveTypeEligibilityRuleRepository ruleRepo;
    @Mock private LeaveTypeEmployeeOverrideRepository overrideRepo;
    @Mock private EmployeeRepository employeeRepo;

    private LeaveEligibilityService service;
    private final LeaveType maternity = LeaveType.builder().id(UUID.randomUUID()).code("MATERNITY").name("Maternity Leave").build();
    private final List<LeaveTypeEligibilityRule> rules = new ArrayList<>();
    private final List<LeaveTypeEmployeeOverride> overrides = new ArrayList<>();
    private final UUID hyderabad = UUID.randomUUID();
    private final UUID sales = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LeaveEligibilityService(ruleRepo, overrideRepo, employeeRepo);
        lenient().when(ruleRepo.findByLeaveTypeId(any())).thenReturn(rules);
        lenient().when(overrideRepo.findByLeaveTypeId(any())).thenReturn(overrides);
    }

    private void rule(String type, String value) {
        rules.add(LeaveTypeEligibilityRule.builder().leaveTypeId(maternity.getId()).ruleType(type).value(value).build());
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
    void genderRule_matchesCaseInsensitively_andAMissingGenderNeverMatches() {
        rule(LeaveTypeEligibilityRule.GENDER, "Female");
        assertTrue(service.isEligible(employee("female", null, null, "FULL_TIME"), maternity));
        assertFalse(service.isEligible(employee("Male", null, null, "FULL_TIME"), maternity));
        assertFalse(service.isEligible(employee(null, null, null, "FULL_TIME"), maternity));
        assertFalse(service.isEligible(employee("  ", null, null, "FULL_TIME"), maternity));
    }

    @Test
    void valuesOfOneKindAreOred_differentKindsAreAnded() {
        rule(LeaveTypeEligibilityRule.LOCATION, hyderabad.toString());
        rule(LeaveTypeEligibilityRule.EMPLOYMENT_TYPE, "FULL_TIME");
        rule(LeaveTypeEligibilityRule.EMPLOYMENT_TYPE, "PART_TIME");
        assertTrue(service.isEligible(employee("Male", sales, hyderabad, "PART_TIME"), maternity));
        assertFalse(service.isEligible(employee("Male", sales, hyderabad, "INTERN"), maternity));
        assertFalse(service.isEligible(employee("Male", sales, UUID.randomUUID(), "FULL_TIME"), maternity));
        assertFalse(service.isEligible(employee("Male", sales, null, "FULL_TIME"), maternity));
    }

    @Test
    void includeOverridesTheRules_excludeOverridesEverything() {
        rule(LeaveTypeEligibilityRule.GENDER, "Female");
        UUID man = employee("Male", null, null, "FULL_TIME");
        UUID woman = employee("Female", null, null, "FULL_TIME");
        overrides.add(LeaveTypeEmployeeOverride.builder().employeeUserId(man).mode(LeaveTypeEmployeeOverride.INCLUDE).build());
        overrides.add(LeaveTypeEmployeeOverride.builder().employeeUserId(woman).mode(LeaveTypeEmployeeOverride.EXCLUDE).build());
        assertTrue(service.isEligible(man, maternity));
        assertFalse(service.isEligible(woman, maternity));
    }

    @Test
    void eligibleTypes_filtersOnlyRestrictedTypes() {
        rule(LeaveTypeEligibilityRule.GENDER, "Female");
        LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave").build();
        when(ruleRepo.findByLeaveTypeId(paid.getId())).thenReturn(List.of());
        when(overrideRepo.findByLeaveTypeId(paid.getId())).thenReturn(List.of());
        UUID man = employee("Male", null, null, "FULL_TIME");
        assertEquals(List.of(paid), service.eligibleTypes(man, List.of(paid, maternity)));
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
