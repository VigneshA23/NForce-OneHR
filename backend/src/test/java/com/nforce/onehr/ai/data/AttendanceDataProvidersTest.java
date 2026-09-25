package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.attendance.AttendanceConfigResponse;
import com.nforce.onehr.dto.attendance.AttendanceExceptionResponse;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.AttendanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for ONEHR - "how many days was I late in the last 30 days, and do I have any
 * active penalties?" answered "3 days" where My Attendance showed 7, and could not say whether the
 * PENALIZED day carried a penalty. Dates mirror the reported employee's log.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceDataProvidersTest {

    @Mock private AttendanceService attendanceService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;

    private static final String EMAIL = "employee@nforceone.com";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private final Employee employee = Employee.builder().userId(UUID.randomUUID()).build();

    @Test
    void lateDaysMatchTheArrivalColumnAndEachExceptionCarriesItsOwnPenaltyVerdict() {
        stubWindow(List.of(
                late(TODAY, 44 * 60 + 8, "LATE"),
                late(TODAY.minusDays(1), 26 * 60 + 30, "LATE"),
                late(TODAY.minusDays(2), 44 * 60 + 37, "LATE"),
                late(TODAY.minusDays(3), 30 * 60 + 52, "LATE"),
                late(TODAY.minusDays(4), 19 * 60 + 24, "HALF_DAY"),
                late(TODAY.minusDays(7), 3 * 3600 + 29 * 60 + 15, "LATE"),
                late(TODAY.minusDays(9), 3600 + 31, "LATE"),
                late(TODAY.minusDays(10), 5 * 60, "PRESENT")), // inside the 10-minute grace: not late
                // Detection lags the page: only three LATE_ARRIVAL rows, which is where "3 days" came from.
                List.of(exception(TODAY.minusDays(1), "LATE_ARRIVAL"),
                        exception(TODAY.minusDays(2), "LATE_ARRIVAL"),
                        exception(TODAY.minusDays(3), "LATE_ARRIVAL"),
                        exception(TODAY.minusDays(8), "NO_ATTENDANCE"),
                        exception(TODAY.minusDays(9), "WORK_HOURS_SHORTAGE")),
                List.of(penalty(TODAY.minusDays(9), "LATE_ARRIVAL")));

        String out = provider().fetch(context()).orElseThrow();

        assertThat(out).contains("- LATE_ARRIVAL (7): 2026-09-25, 2026-09-24, 2026-09-23, 2026-09-22, "
                + "2026-09-21, 2026-09-18, 2026-09-16");
        assertThat(out).contains("exactly 9, one per line");
        assertThat(out).contains("8. 2026-09-16: LATE_ARRIVAL (late by 1h 0m 31s) - PENALIZED (0.5 day(s) deducted)");
        // Same date, different exception: the penalty belongs to the late arrival only.
        assertThat(out).contains("9. 2026-09-16: WORK_HOURS_SHORTAGE - not penalized");
        assertThat(out).contains("7. 2026-09-17: NO_ATTENDANCE - not penalized");
        assertThat(out).contains("exactly 1:\n1. 2026-09-16: LATE_ARRIVAL (0.5 day(s) deducted)");
        assertThat(out).doesNotContain("2026-09-15: LATE_ARRIVAL");
    }

    @Test
    void noActivePenaltyIsStatedRatherThanLeftOut() {
        stubWindow(List.of(late(TODAY, 30 * 60, "LATE")), List.of(), List.of());

        String out = provider().fetch(context()).orElseThrow();

        assertThat(out).contains("Active penalties from 2026-06-28 to 2026-09-25: none - you have no active attendance penalty.");
        assertThat(out).contains("1. 2026-09-25: LATE_ARRIVAL (late by 30m 0s) - not penalized");
    }

    private void stubWindow(List<AttendanceResponse> rows, List<AttendanceExceptionResponse> exceptions,
                            List<AttendancePenalty> penalties) {
        LocalDate from = TODAY.minusDays(29);
        when(attendanceService.currentWorkDate(EMAIL)).thenReturn(TODAY);
        when(attendanceService.getMyHistory(EMAIL, from, TODAY)).thenReturn(rows);
        when(attendanceService.getMyExceptions(EMAIL, from, TODAY)).thenReturn(exceptions);
        when(attendanceService.getConfig(EMAIL)).thenReturn(AttendanceConfigResponse.builder().lateGraceMinutes(10).build());
        when(employeeRepository.findByUser_Email(EMAIL)).thenReturn(Optional.of(employee));
        when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetweenAndStatus(
                eq(employee.getUserId()), eq(TODAY.minusDays(89)), eq(TODAY), eq(AttendancePenaltyStatus.PENDING_REVIEW)))
                .thenReturn(penalties);
    }

    private AttendanceDataProviders.MyHistory provider() {
        return new AttendanceDataProviders.MyHistory(attendanceService, employeeRepository, attendancePenaltyRepository);
    }

    private static AttendanceResponse late(LocalDate date, int secondsLate, String status) {
        LocalDateTime shiftStart = date.atTime(9, 30);
        return AttendanceResponse.builder().workDate(date).status(status).lateByMinutes(secondsLate / 60)
                .shiftStartAt(shiftStart).checkInAt(shiftStart.plusSeconds(secondsLate)).workedMinutes(300).build();
    }

    private static AttendanceExceptionResponse exception(LocalDate date, String type) {
        return AttendanceExceptionResponse.builder().id(UUID.randomUUID()).exceptionDate(date).exceptionType(type).status("OPEN").build();
    }

    private AttendancePenalty penalty(LocalDate date, String type) {
        return AttendancePenalty.builder().id(UUID.randomUUID()).employeeUserId(employee.getUserId())
                .incidentDate(date).discrepancyType(type).status(AttendancePenaltyStatus.PENDING_REVIEW)
                .deductionDays(new BigDecimal("0.5")).evaluatedAt(LocalDateTime.now()).penalizedOn(LocalDateTime.now()).build();
    }

    private AssistantRequestContext context() {
        return AssistantRequestContext.builder()
                .userId(UUID.randomUUID())
                .actorEmail(EMAIL)
                .primaryRoleCode("EMPLOYEE")
                .shellRole(ShellRole.EMPLOYEE)
                .audiences(Set.of(AudienceBucket.EMPLOYEE))
                .build();
    }
}
