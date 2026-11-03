package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeWeeklyOffPolicyAssignment;
import com.nforce.onehr.entity.Holiday;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.entity.WeeklyOffPolicyVersion;
import com.nforce.onehr.repository.HolidayRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WorkingDayService — the shared expected-working-days calculation behind Team Effort and Team
 * Punctuality. Weekly-off resolution is effective-dated (Phase 3): "which policy applies" is
 * mocked via {@link EmployeeWeeklyOffPolicyAssignmentResolver}/{@link
 * WeeklyOffPolicyVersionResolver} rather than set directly on the {@link Employee} fixture (that
 * field is now a non-authoritative display cache only — see its own Javadoc).
 */
@ExtendWith(MockitoExtension.class)
class WorkingDayServiceTest {

    @Mock private HolidayRepository holidayRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private EmployeeWeeklyOffPolicyAssignmentResolver employeeWeeklyOffPolicyAssignmentResolver;
    @Mock private WeeklyOffPolicyVersionResolver weeklyOffPolicyVersionResolver;

    private WorkingDayService service;

    private final UUID empId = UUID.randomUUID();
    private final Location locationA = Location.builder().id(UUID.randomUUID()).name("Bengaluru").build();
    private final Location locationB = Location.builder().id(UUID.randomUUID()).name("Pune").build();

    // Monday 3 Aug 2026 .. Sunday 9 Aug 2026 — one full calendar week.
    private final LocalDate monday = LocalDate.of(2026, 8, 3);
    private final LocalDate sunday = LocalDate.of(2026, 8, 9);

    @BeforeEach
    void setUp() {
        service = new WorkingDayService(holidayRepository, leaveRequestRepository,
                employeeWeeklyOffPolicyAssignmentResolver, weeklyOffPolicyVersionResolver);
        lenient().when(holidayRepository.findByLocation_IdInAndActiveTrue(any())).thenReturn(List.of());
        lenient().when(leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any(), any(), any()))
                .thenReturn(List.of());
        // Defaults (no stubbing below) resolve to empty batches via Mockito's own
        // empty-collection default answer — i.e. "no effective assignment," exactly the
        // Saturday/Sunday-fallback case every test exercises unless it calls stubPolicy itself.
    }

    private Employee employee(Location location, WeeklyOffPolicy policy, LocalDate joiningDate) {
        Employee emp = Employee.builder().userId(empId).fullName("Test Employee")
                .location(location).joiningDate(joiningDate).build();
        if (policy != null) {
            stubPolicy(empId, policy, LocalDate.of(2000, 1, 1));
        }
        return emp;
    }

    /** Stubs {@code employeeUserId} as effectively assigned to {@code policy} (and its current off days) from {@code effectiveFrom}. */
    private void stubPolicy(UUID employeeUserId, WeeklyOffPolicy policy, LocalDate effectiveFrom) {
        UUID policyId = policy.getId() != null ? policy.getId() : UUID.randomUUID();
        policy.setId(policyId);
        EmployeeWeeklyOffPolicyAssignment assignment = EmployeeWeeklyOffPolicyAssignment.builder()
                .employeeUserId(employeeUserId).weeklyOffPolicy(policy).effectiveFrom(effectiveFrom).build();
        lenient().when(employeeWeeklyOffPolicyAssignmentResolver.resolveHistoryBatch(any()))
                .thenReturn(Map.of(employeeUserId, List.of(assignment)));
        WeeklyOffPolicyVersion version = WeeklyOffPolicyVersion.builder()
                .weeklyOffPolicy(policy).offDays(policy.getOffDays()).effectiveFrom(effectiveFrom).build();
        lenient().when(weeklyOffPolicyVersionResolver.resolveHistoryBatch(any()))
                .thenReturn(Map.of(policyId, List.of(version)));
    }

    @Test
    void defaultWeekendFallback_excludesSaturdayAndSunday_whenNoWeeklyOffPolicyAssigned() {
        Employee employee = employee(null, null, null);

        WorkingDaySchedule schedule = service.computeExpectedWorkingDays(employee, monday, sunday);

        // Mon-Fri = 5 working days; Sat/Sun excluded.
        assertEquals(5, schedule.getExpectedWorkingDays());
        assertFalse(schedule.getWorkingDates().contains(monday.plusDays(5))); // Saturday
        assertFalse(schedule.getWorkingDates().contains(sunday)); // Sunday
    }

    @Test
    void customWeeklyOffPolicy_excludesConfiguredDaysInsteadOfSaturdaySunday() {
        WeeklyOffPolicy fridaySaturdayOff = WeeklyOffPolicy.builder().offDays("FRIDAY,SATURDAY").build();
        Employee employee = employee(null, fridaySaturdayOff, null);

        WorkingDaySchedule schedule = service.computeExpectedWorkingDays(employee, monday, sunday);

        // Mon,Tue,Wed,Thu,Sun = 5 working days; Fri/Sat excluded, Sunday now counts as working.
        assertEquals(5, schedule.getExpectedWorkingDays());
        assertTrue(schedule.getWorkingDates().contains(sunday));
        assertFalse(schedule.getWorkingDates().contains(monday.plusDays(4))); // Friday
        assertFalse(schedule.getWorkingDates().contains(monday.plusDays(5))); // Saturday
    }

    @Test
    void nullOffDaysString_onAssignedPolicy_fallsBackToSaturdaySunday() {
        WeeklyOffPolicy blankPolicy = WeeklyOffPolicy.builder().offDays("").build();
        Employee employee = employee(null, blankPolicy, null);

        WorkingDaySchedule schedule = service.computeExpectedWorkingDays(employee, monday, sunday);

        assertEquals(5, schedule.getExpectedWorkingDays());
    }

    @Test
    void holiday_removesThatDateFromWorkingDays() {
        LocalDate wednesday = monday.plusDays(2);
        when(holidayRepository.findByLocation_IdInAndActiveTrue(Set.of(locationA.getId())))
                .thenReturn(List.of(Holiday.builder().holidayDate(wednesday).location(locationA).active(true).build()));
        Employee employee = employee(locationA, null, null);

        WorkingDaySchedule schedule = service.computeExpectedWorkingDays(employee, monday, sunday);

        assertEquals(4, schedule.getExpectedWorkingDays());
        assertFalse(schedule.getWorkingDates().contains(wednesday));
    }

    @Test
    void multipleLocations_bulk_appliesEachEmployeesOwnLocationHolidaysOnly() {
        LocalDate wednesday = monday.plusDays(2);
        LocalDate thursday = monday.plusDays(3);
        when(holidayRepository.findByLocation_IdInAndActiveTrue(Set.of(locationA.getId(), locationB.getId())))
                .thenReturn(List.of(
                        Holiday.builder().holidayDate(wednesday).location(locationA).active(true).build(),
                        Holiday.builder().holidayDate(thursday).location(locationB).active(true).build()));

        UUID empAId = UUID.randomUUID();
        UUID empBId = UUID.randomUUID();
        Employee empA = Employee.builder().userId(empAId).fullName("Employee A").location(locationA).build();
        Employee empB = Employee.builder().userId(empBId).fullName("Employee B").location(locationB).build();

        Map<UUID, WorkingDaySchedule> result = service.computeExpectedWorkingDaysBulk(List.of(empA, empB), monday, sunday);

        assertFalse(result.get(empAId).getWorkingDates().contains(wednesday));
        assertTrue(result.get(empAId).getWorkingDates().contains(thursday)); // B's holiday, not A's
        assertFalse(result.get(empBId).getWorkingDates().contains(thursday));
        assertTrue(result.get(empBId).getWorkingDates().contains(wednesday)); // A's holiday, not B's
    }

    @Test
    void approvedFullDayLeave_removesEveryDateInTheLeaveRange() {
        LocalDate tuesday = monday.plusDays(1);
        LocalDate wednesday = monday.plusDays(2);
        when(leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any(), any(), any()))
                .thenReturn(List.of(LeaveRequest.builder()
                        .employeeUserId(empId).leaveType(new LeaveType())
                        .startDate(tuesday).endDate(wednesday).halfDay(false)
                        .totalDays(java.math.BigDecimal.valueOf(2)).employeeReason("x").build()));
        Employee employee = employee(null, null, null);

        WorkingDaySchedule schedule = service.computeExpectedWorkingDays(employee, monday, sunday);

        assertEquals(3, schedule.getExpectedWorkingDays()); // Mon, Thu, Fri only
        assertFalse(schedule.getWorkingDates().contains(tuesday));
        assertFalse(schedule.getWorkingDates().contains(wednesday));
    }

    @Test
    void approvedHalfDayLeave_removesTheWholeDate_becausePunctualityIsWholeDayGranularity() {
        LocalDate tuesday = monday.plusDays(1);
        when(leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any(), any(), any()))
                .thenReturn(List.of(LeaveRequest.builder()
                        .employeeUserId(empId).leaveType(new LeaveType())
                        .startDate(tuesday).endDate(tuesday).halfDay(true)
                        .totalDays(java.math.BigDecimal.valueOf(0.5)).employeeReason("x").build()));
        Employee employee = employee(null, null, null);

        WorkingDaySchedule schedule = service.computeExpectedWorkingDays(employee, monday, sunday);

        assertFalse(schedule.getWorkingDates().contains(tuesday));
        assertEquals(4, schedule.getExpectedWorkingDays());
    }

    @Test
    void joiningDate_clampsRangeStart_soDaysBeforeJoiningAreNeverExpected() {
        LocalDate wednesday = monday.plusDays(2);
        Employee employee = employee(null, null, wednesday);

        WorkingDaySchedule schedule = service.computeExpectedWorkingDays(employee, monday, sunday);

        // Wed, Thu, Fri only — Mon/Tue are before joining, Sat/Sun are the weekend.
        assertEquals(3, schedule.getExpectedWorkingDays());
        assertFalse(schedule.getWorkingDates().contains(monday));
        assertTrue(schedule.getWorkingDates().contains(wednesday));
    }

    @Test
    void inclusiveBoundaries_bothFromAndToCountWhenTheyAreWorkingDays() {
        Employee employee = employee(null, null, null);
        LocalDate tuesday = monday.plusDays(1);
        LocalDate thursday = monday.plusDays(3);

        WorkingDaySchedule schedule = service.computeExpectedWorkingDays(employee, tuesday, thursday);

        assertEquals(3, schedule.getExpectedWorkingDays());
        assertTrue(schedule.getWorkingDates().contains(tuesday));
        assertTrue(schedule.getWorkingDates().contains(thursday));
    }

    @Test
    void entirelyBeforeJoiningDate_range_hasZeroExpectedWorkingDays() {
        Employee employee = employee(null, null, sunday.plusDays(30));

        WorkingDaySchedule schedule = service.computeExpectedWorkingDays(employee, monday, sunday);

        assertEquals(0, schedule.getExpectedWorkingDays());
        assertTrue(schedule.getWorkingDates().isEmpty());
    }

    @Test
    void emptyEmployeeList_bulk_returnsEmptyMap() {
        Map<UUID, WorkingDaySchedule> result = service.computeExpectedWorkingDaysBulk(List.of(), monday, sunday);

        assertTrue(result.isEmpty());
    }

    // ── Phase 3: a policy change partway through the range must only affect dates from its own
    //    effective date onward — never resolved once for the whole range. ──────────────────────

    @Test
    void policyReassignment_withinRange_onlyAffectsDatesFromItsOwnEffectiveDateOnward() {
        // Default (Sat/Sun) through Wednesday, then Friday/Saturday-off from Thursday onward.
        LocalDate thursday = monday.plusDays(3);
        WeeklyOffPolicy fridaySaturdayOff = WeeklyOffPolicy.builder().id(UUID.randomUUID()).offDays("FRIDAY,SATURDAY").build();
        EmployeeWeeklyOffPolicyAssignment assignment = EmployeeWeeklyOffPolicyAssignment.builder()
                .employeeUserId(empId).weeklyOffPolicy(fridaySaturdayOff).effectiveFrom(thursday).build();
        when(employeeWeeklyOffPolicyAssignmentResolver.resolveHistoryBatch(any()))
                .thenReturn(Map.of(empId, List.of(assignment)));
        WeeklyOffPolicyVersion version = WeeklyOffPolicyVersion.builder()
                .weeklyOffPolicy(fridaySaturdayOff).offDays("FRIDAY,SATURDAY").effectiveFrom(thursday).build();
        when(weeklyOffPolicyVersionResolver.resolveHistoryBatch(any()))
                .thenReturn(Map.of(fridaySaturdayOff.getId(), List.of(version)));
        Employee employee = Employee.builder().userId(empId).fullName("Test Employee").build();

        WorkingDaySchedule schedule = service.computeExpectedWorkingDays(employee, monday, sunday);

        // Before Thursday: default Sat/Sun weekend (not yet effective) — Mon/Tue/Wed are working.
        assertTrue(schedule.getWorkingDates().contains(monday));
        assertTrue(schedule.getWorkingDates().contains(monday.plusDays(1)));
        assertTrue(schedule.getWorkingDates().contains(monday.plusDays(2)));
        // From Thursday onward: Friday/Saturday off under the new policy, Sunday now works.
        assertTrue(schedule.getWorkingDates().contains(thursday));
        assertFalse(schedule.getWorkingDates().contains(monday.plusDays(4))); // Friday
        assertFalse(schedule.getWorkingDates().contains(monday.plusDays(5))); // Saturday
        assertTrue(schedule.getWorkingDates().contains(sunday));
    }

    // ── nextWorkingDay (not currently called by production code — see its own Javadoc; covered
    //    here as a small, independently correct utility in its own right) ─────────────────────────

    @Test
    void nextWorkingDay_isTheImmediateNextDay_whenItIsAlreadyAWorkingDay() {
        // Thursday, default Sat/Sun weekend — Friday is an ordinary working day, no skip needed.
        LocalDate thursday = monday.plusDays(3);
        Employee employee = employee(null, null, null);

        LocalDate next = service.nextWorkingDay(employee, thursday);

        assertEquals(monday.plusDays(4), next); // Friday
    }

    @Test
    void nextWorkingDay_skipsSaturdayAndSunday_whenCreatedOnFridayWithDefaultWeekend() {
        // Friday creation, no WeeklyOffPolicy assigned (default Sat/Sun) — must skip straight to
        // the following Monday, not Saturday. Matches the ticket's own worked example.
        LocalDate friday = monday.plusDays(4);
        Employee employee = employee(null, null, null);

        LocalDate next = service.nextWorkingDay(employee, friday);

        assertEquals(monday.plusDays(7), next); // the following Monday
    }

    @Test
    void nextWorkingDay_respectsACustomWeeklyOffPolicy_insteadOfSaturdaySunday() {
        // Friday/Saturday off (not Sat/Sun) — from Thursday, both Friday and Saturday are skipped
        // (off under THIS policy); Sunday is an ordinary working day under it.
        WeeklyOffPolicy fridaySaturdayOff = WeeklyOffPolicy.builder().offDays("FRIDAY,SATURDAY").build();
        LocalDate thursday = monday.plusDays(3);
        Employee employee = employee(null, fridaySaturdayOff, null);

        LocalDate next = service.nextWorkingDay(employee, thursday);

        assertEquals(monday.plusDays(6), next); // Sunday — the first working day under this policy
    }

    @Test
    void nextWorkingDay_skipsALocationHoliday() {
        LocalDate tuesday = monday.plusDays(1);
        LocalDate wednesday = monday.plusDays(2);
        when(holidayRepository.findByLocation_IdInAndActiveTrue(Set.of(locationA.getId())))
                .thenReturn(List.of(Holiday.builder().holidayDate(tuesday).location(locationA).active(true).build()));
        Employee employee = employee(locationA, null, null);

        LocalDate next = service.nextWorkingDay(employee, monday);

        assertEquals(wednesday, next); // Tuesday is a holiday, skipped
    }

    @Test
    void nextWorkingDay_combinesWeeklyOffAndHoliday_skippingBoth() {
        // Friday creation, default Sat/Sun weekend, AND Monday is also a holiday — must land on
        // Tuesday, skipping Saturday, Sunday, and the Monday holiday all in one pass.
        LocalDate friday = monday.plusDays(4);
        LocalDate nextMonday = monday.plusDays(7);
        LocalDate nextTuesday = monday.plusDays(8);
        when(holidayRepository.findByLocation_IdInAndActiveTrue(Set.of(locationA.getId())))
                .thenReturn(List.of(Holiday.builder().holidayDate(nextMonday).location(locationA).active(true).build()));
        Employee employee = employee(locationA, null, null);

        LocalDate next = service.nextWorkingDay(employee, friday);

        assertEquals(nextTuesday, next);
    }

    /**
     * Code-review corrective pass, finding 6: OrgService's own create/update validation is
     * supposed to make an all-7-days-off WeeklyOffPolicy impossible to save (see
     * OrgServiceWeeklyOffPolicyTest) — this is the defensive fallback for data that predates that
     * validation, or was written directly. Must fail loudly and quickly, never hang the
     * employee-creation request forever searching for a working day that will never come.
     */
    @Test
    void nextWorkingDay_allSevenDaysOff_failsLoudly_neverHangsForever() {
        WeeklyOffPolicy everyDayOff = WeeklyOffPolicy.builder()
                .offDays("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY").build();
        Employee employee = employee(null, everyDayOff, null);

        assertThrows(IllegalStateException.class, () -> service.nextWorkingDay(employee, monday));
    }
}
