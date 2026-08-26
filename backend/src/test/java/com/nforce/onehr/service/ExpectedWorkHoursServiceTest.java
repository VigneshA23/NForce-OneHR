package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.LeaveDurationType;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.LeaveRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Section 9/16 — adjusted expected work hours after approved hourly/quarter-day leave. */
@ExtendWith(MockitoExtension.class)
class ExpectedWorkHoursServiceTest {

    @Mock private LeaveRequestRepository leaveRequestRepository;

    private ExpectedWorkHoursService service;

    private final UUID employeeId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 8, 10);
    // 9:00-18:00 = 540 minutes (9 hours).
    private final Shift nineHourShift = Shift.builder().id(UUID.randomUUID()).name("Regular")
            .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build();

    @BeforeEach
    void setUp() {
        service = new ExpectedWorkHoursService(leaveRequestRepository);
    }

    private Employee employeeWithShift(Shift shift) {
        return Employee.builder().userId(employeeId).fullName("Test Employee").shift(shift).build();
    }

    @Test
    void noShift_returnsNull() {
        Employee noShift = employeeWithShift(null);
        assertNull(service.shiftMinutes(noShift));
    }

    @Test
    void noPartialLeave_returnsFullShiftMinutes() {
        Employee employee = employeeWithShift(nineHourShift);
        assertEquals(540L, service.adjustedExpectedMinutes(employee, date, null));
    }

    @Test
    void quarterDayLeave_reducesExpectedMinutesByAQuarterOfTheShift() {
        Employee employee = employeeWithShift(nineHourShift);
        LeaveRequest quarterDay = LeaveRequest.builder().employeeUserId(employeeId)
                .startDate(date).endDate(date).durationType(LeaveDurationType.QUARTER_DAY).build();

        // 540 * 0.25 = 135 -> 540 - 135 = 405
        assertEquals(405L, service.adjustedExpectedMinutes(employee, date, quarterDay));
    }

    @Test
    void hourlyLeave_reducesExpectedMinutesByExactlyTheRequestedHours() {
        Employee employee = employeeWithShift(nineHourShift);
        LeaveRequest hourly = LeaveRequest.builder().employeeUserId(employeeId)
                .startDate(date).endDate(date).durationType(LeaveDurationType.HOURLY)
                .leaveHours(new BigDecimal("2")).build();

        // 540 - (2 * 60) = 420
        assertEquals(420L, service.adjustedExpectedMinutes(employee, date, hourly));
    }

    @Test
    void adjustedExpectedMinutes_neverGoesNegative() {
        // Pathological: leaveHours somehow exceeds the shift — floor at 0, don't go negative.
        Shift oneHourShift = Shift.builder().id(UUID.randomUUID()).name("Short")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0)).build();
        Employee employee = employeeWithShift(oneHourShift);
        LeaveRequest hourly = LeaveRequest.builder().employeeUserId(employeeId)
                .startDate(date).endDate(date).durationType(LeaveDurationType.HOURLY)
                .leaveHours(new BigDecimal("5")).build();

        assertEquals(0L, service.adjustedExpectedMinutes(employee, date, hourly));
    }

    @Test
    void noShift_returnsNullEvenWithApprovedPartialLeave() {
        Employee employee = employeeWithShift(null);
        LeaveRequest quarterDay = LeaveRequest.builder().employeeUserId(employeeId)
                .startDate(date).endDate(date).durationType(LeaveDurationType.QUARTER_DAY).build();

        assertNull(service.adjustedExpectedMinutes(employee, date, quarterDay));
    }

    @Test
    void loadPartialHourLeaveByEmployeeDate_onlyReturnsHourlyAndQuarterDayTypes() {
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndDurationTypeInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(LeaveRequest.builder().employeeUserId(employeeId)
                        .startDate(date).endDate(date).durationType(LeaveDurationType.HOURLY)
                        .leaveHours(new BigDecimal("1")).build()));

        Map<String, LeaveRequest> result = service.loadPartialHourLeaveByEmployeeDate(List.of(employeeId), date, date);

        assertEquals(1, result.size());
        assertEquals(LeaveDurationType.HOURLY, result.get(employeeId + "|" + date).getDurationType());
    }

    @Test
    void adjustedExpectedMinutes_singleArgOverload_loadsFromRepository() {
        Employee employee = employeeWithShift(nineHourShift);
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndDurationTypeInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), any(), any(), any(), any()))
                .thenReturn(List.of(LeaveRequest.builder().employeeUserId(employeeId)
                        .startDate(date).endDate(date).durationType(LeaveDurationType.QUARTER_DAY).build()));

        assertEquals(405L, service.adjustedExpectedMinutes(employee, date));
    }
}
