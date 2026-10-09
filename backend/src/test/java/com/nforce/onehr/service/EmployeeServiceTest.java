package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.BirthdayEntryDto;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

// ONEHR: Birthday Widget on Dashboard — org-wide "today + next 7 days" birthday list.
//
// EmployeeService resolves "today" from the real clock (LocalDate.now(zone), same as every other
// date-sensitive method on this service — see submit()/updateEmployee() elsewhere in the class),
// so these tests compute expected dates relative to that same real "today" rather than pinning a
// fixed calendar date, matching this codebase's existing convention for time-sensitive service
// tests (no injected Clock exists on this service to override).
@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {

    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttendanceProperties attendanceProperties;

    @InjectMocks
    private EmployeeService employeeService;

    private LocalDate today;

    @BeforeEach
    void setUp() {
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    private Employee employee(String name, LocalDate dob, boolean active, LocalDate lastWorkingDay) {
        User user = User.builder().id(UUID.randomUUID()).email(name.toLowerCase() + "@test.com")
                .active(active).roles(Set.of(Role.builder().id(1).code("EMPLOYEE").displayName("Employee").build()))
                .build();
        return Employee.builder()
                .userId(UUID.randomUUID())
                .user(user)
                .fullName(name)
                .dateOfBirth(dob)
                .department(Department.builder().id(UUID.randomUUID()).name("Engineering").build())
                .lastWorkingDay(lastWorkingDay)
                .build();
    }

    /**
     * A date-of-birth whose month/day is exactly `offsetDays` from today, in birth year 2000 —
     * deliberately a leap year so a target that lands on Feb 29 (possible when offsetDays crosses
     * that date in a leap year) always constructs a valid LocalDate.
     */
    private LocalDate dobOffsetFromToday(int offsetDays) {
        LocalDate target = today.plusDays(offsetDays);
        return LocalDate.of(2000, target.getMonthValue(), target.getDayOfMonth());
    }

    @Test
    void includesAnEmployeeWhoseBirthdayIsToday() {
        LocalDate dob = dobOffsetFromToday(0);
        when(employeeRepository.findAllWithDetails())
                .thenReturn(List.of(employee("Asha", dob, true, null)));

        List<BirthdayEntryDto> result = employeeService.listUpcomingBirthdays();

        assertEquals(1, result.size());
        assertTrue(result.get(0).isToday());
        assertEquals(0, result.get(0).getDaysUntil());
        assertEquals(today.getMonthValue(), result.get(0).getBirthdayMonth());
        assertEquals(today.getDayOfMonth(), result.get(0).getBirthdayDay());
    }

    @Test
    void includesAnEmployeeWhoseBirthdayIsWithinTheNextSevenDays() {
        when(employeeRepository.findAllWithDetails())
                .thenReturn(List.of(employee("Ben", dobOffsetFromToday(5), true, null)));

        List<BirthdayEntryDto> result = employeeService.listUpcomingBirthdays();

        assertEquals(1, result.size());
        assertFalse(result.get(0).isToday());
        assertEquals(5, result.get(0).getDaysUntil());
    }

    @Test
    void excludesAnEmployeeWhoseBirthdayIsMoreThanSevenDaysAway() {
        when(employeeRepository.findAllWithDetails())
                .thenReturn(List.of(employee("Cara", dobOffsetFromToday(14), true, null)));

        assertTrue(employeeService.listUpcomingBirthdays().isEmpty());
    }

    @Test
    void wrapsAcrossTheYearBoundaryWhenTheNearestOccurrenceIsNextYear() {
        // A birthday that fell 5 days ago (this year) must resolve to NEXT year's occurrence
        // (360-ish days away, so excluded from the 7-day window) rather than being reported as
        // "5 days ago" or otherwise mishandled — this is exactly the Dec 28 -> Jan 2 boundary case
        // from the user story, generalized to run correctly regardless of which day the suite runs.
        when(employeeRepository.findAllWithDetails())
                .thenReturn(List.of(employee("Dev", dobOffsetFromToday(-5), true, null)));

        assertTrue(employeeService.listUpcomingBirthdays().isEmpty());
    }

    @Test
    void handlesALeapDayBirthdayWithoutThrowing() {
        when(employeeRepository.findAllWithDetails())
                .thenReturn(List.of(employee("Priya", LocalDate.of(2000, 2, 29), true, null)));

        assertDoesNotThrow(() -> employeeService.listUpcomingBirthdays());
    }

    @Test
    void excludesAnEmployeeWithNoDateOfBirthOnFile() {
        when(employeeRepository.findAllWithDetails())
                .thenReturn(List.of(employee("Eli", null, true, null)));

        assertTrue(employeeService.listUpcomingBirthdays().isEmpty());
    }

    @Test
    void excludesADeactivatedEmployee() {
        when(employeeRepository.findAllWithDetails())
                .thenReturn(List.of(employee("Fay", dobOffsetFromToday(0), false, null)));

        assertTrue(employeeService.listUpcomingBirthdays().isEmpty());
    }

    @Test
    void excludesAnEmployeeAlreadyPastTheirLastWorkingDay() {
        when(employeeRepository.findAllWithDetails())
                .thenReturn(List.of(employee("Gus", dobOffsetFromToday(0), true, today.minusDays(1))));

        assertTrue(employeeService.listUpcomingBirthdays().isEmpty());
    }

    @Test
    void includesAnEmployeeWhoseLastWorkingDayIsStillInTheFuture() {
        when(employeeRepository.findAllWithDetails())
                .thenReturn(List.of(employee("Hana", dobOffsetFromToday(0), true, today.plusDays(10))));

        assertEquals(1, employeeService.listUpcomingBirthdays().size());
    }

    @Test
    void sortsSoonestBirthdaysFirst() {
        when(employeeRepository.findAllWithDetails()).thenReturn(List.of(
                employee("Later", dobOffsetFromToday(6), true, null),
                employee("Sooner", dobOffsetFromToday(1), true, null),
                employee("Now", dobOffsetFromToday(0), true, null)
        ));

        List<BirthdayEntryDto> result = employeeService.listUpcomingBirthdays();

        assertEquals(List.of("Now", "Sooner", "Later"),
                result.stream().map(BirthdayEntryDto::getFullName).toList());
    }

    @Test
    void neverExposesBirthYear() {
        boolean hasYearField = false;
        for (var field : BirthdayEntryDto.class.getDeclaredFields()) {
            if (field.getName().toLowerCase().contains("year")) hasYearField = true;
        }
        assertFalse(hasYearField, "BirthdayEntryDto must never carry a birth-year-bearing field");
    }
}
