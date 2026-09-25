package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.dto.attendance.AttendanceExceptionResponse;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the "yesterday" date bug (ONEHR — chatbot misreads "yesterday"):
 * {@code MyExceptions} used to compute its lookback window from {@code LocalDate.now()}, the
 * JVM/server's default zone, instead of the employee's own business zone — the same class of bug
 * that (separately) left the model with no ground truth for "today" at all. See
 * {@code PromptBuilderTest#systemPromptStatesTheCurrentDate} for that half of the fix.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceDataProvidersTest {

    @Mock private AttendanceService attendanceService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttendanceRulesService attendanceRulesService;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;

    private static final String EMAIL = "employee@nforceone.com";

    @Test
    void myExceptionsLookbackWindowUsesTheEmployeesOwnZoneNotTheServersDefault() {
        // A zone deliberately far from whatever the CI/dev machine's own default zone is, so this
        // test would fail if the fix regressed back to a bare LocalDate.now().
        ZoneId employeeZone = ZoneId.of("Pacific/Kiritimati"); // UTC+14
        Employee employee = Employee.builder().userId(UUID.randomUUID()).build();
        when(employeeRepository.findByUser_Email(EMAIL)).thenReturn(Optional.of(employee));
        when(attendanceRulesService.resolveEmployeeZoneId(employee)).thenReturn(employeeZone);

        AttendanceDataProviders.MyExceptions provider =
                new AttendanceDataProviders.MyExceptions(attendanceService, employeeRepository, attendanceRulesService);

        LocalDate expectedToday = LocalDate.now(employeeZone);
        provider.fetch(context());

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(attendanceService).getMyExceptions(eq(EMAIL), from.capture(), to.capture());
        assertThat(to.getValue()).isEqualTo(expectedToday);
        // 7 days counting today (today and the six before it), matching the regularization
        // window's own documented convention — not today-plus-seven.
        assertThat(from.getValue()).isEqualTo(expectedToday.minusDays(6));
    }

    @Test
    void myExceptionsFallsBackToTheOrgDefaultZoneWhenNoEmployeeProfileExists() {
        ZoneId orgZone = ZoneId.of("Pacific/Kiritimati");
        when(employeeRepository.findByUser_Email(EMAIL)).thenReturn(Optional.empty());
        when(attendanceRulesService.getDefaultZoneId()).thenReturn(orgZone);

        AttendanceDataProviders.MyExceptions provider =
                new AttendanceDataProviders.MyExceptions(attendanceService, employeeRepository, attendanceRulesService);

        LocalDate expectedToday = LocalDate.now(orgZone);
        provider.fetch(context());

        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(attendanceService).getMyExceptions(eq(EMAIL), org.mockito.ArgumentMatchers.any(), to.capture());
        assertThat(to.getValue()).isEqualTo(expectedToday);
    }

    @Test
    void myExceptionsStatesTheTrueTotalAndDoesNotSilentlyDropRowsWithinItsHeadroom() {
        // Regression for ONEHR — chatbot excluded a real late-arrival day from a "last 7 days"
        // answer. Root cause: a real employee's 7-day window held 7 exception rows (a day can carry
        // more than one, e.g. a LATE_ARRIVAL and a WORK_HOURS_SHORTAGE on the same date), and the
        // old MAX_ROWS of 5 truncated the two oldest away before the model ever saw them, while the
        // header still claimed "exactly 5". This asserts all 7 are both requested and stated.
        ZoneId employeeZone = ZoneId.of("Pacific/Kiritimati");
        Employee employee = Employee.builder().userId(UUID.randomUUID()).build();
        when(employeeRepository.findByUser_Email(EMAIL)).thenReturn(Optional.of(employee));
        when(attendanceRulesService.resolveEmployeeZoneId(employee)).thenReturn(employeeZone);

        LocalDate today = LocalDate.now(employeeZone);
        List<AttendanceExceptionResponse> sevenRows = List.of(
                exception(today, "LATE_ARRIVAL"),
                exception(today.minusDays(1), "WORK_HOURS_SHORTAGE"),
                exception(today.minusDays(2), "WORK_HOURS_SHORTAGE"),
                exception(today.minusDays(2), "LATE_ARRIVAL"),
                exception(today.minusDays(3), "NO_ATTENDANCE"),
                exception(today.minusDays(6), "MISSING_PUNCH"),
                exception(today.minusDays(6), "LATE_ARRIVAL"));
        when(attendanceService.getMyExceptions(eq(EMAIL), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(sevenRows);

        AttendanceDataProviders.MyExceptions provider =
                new AttendanceDataProviders.MyExceptions(attendanceService, employeeRepository, attendanceRulesService);

        Optional<String> result = provider.fetch(context());

        assertThat(result).isPresent();
        assertThat(result.get()).contains("exactly 7 exception(s)");
        assertThat(result.get()).contains(today.minusDays(6).toString() + ": MISSING_PUNCH");
        assertThat(result.get()).contains(today.minusDays(6).toString() + ": LATE_ARRIVAL");
        // Regression for a second, separate failure mode found alongside the truncation bug: once
        // every row reached the model, a "which days was I late" question still dropped the oldest
        // LATE_ARRIVAL by scanning the flat mixed-type list itself. The grouped-by-type line below
        // lets that question be answered by quoting rather than filtering.
        assertThat(result.get()).contains("- LATE_ARRIVAL (3): " + today + ", " + today.minusDays(2) + ", " + today.minusDays(6));
    }

    @Test
    void myExceptionsAdmitsTruncationInsteadOfMisstatingTheTotalWhenTrulyOverHeadroom() {
        ZoneId employeeZone = ZoneId.of("Pacific/Kiritimati");
        Employee employee = Employee.builder().userId(UUID.randomUUID()).build();
        when(employeeRepository.findByUser_Email(EMAIL)).thenReturn(Optional.of(employee));
        when(attendanceRulesService.resolveEmployeeZoneId(employee)).thenReturn(employeeZone);

        LocalDate today = LocalDate.now(employeeZone);
        List<AttendanceExceptionResponse> tooMany = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            tooMany.add(exception(today, "LATE_ARRIVAL"));
        }
        when(attendanceService.getMyExceptions(eq(EMAIL), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(tooMany);

        AttendanceDataProviders.MyExceptions provider =
                new AttendanceDataProviders.MyExceptions(attendanceService, employeeRepository, attendanceRulesService);

        Optional<String> result = provider.fetch(context());

        assertThat(result).isPresent();
        assertThat(result.get()).contains("25 exception(s)");
        assertThat(result.get()).contains("only the most recent 20 are listed");
        assertThat(result.get()).doesNotContain("exactly 25");
    }

    private AttendanceExceptionResponse exception(LocalDate date, String type) {
        return AttendanceExceptionResponse.builder()
                .id(UUID.randomUUID())
                .exceptionDate(date)
                .exceptionType(type)
                .status("OPEN")
                .build();
    }

    @Test
    void myPenaltiesReturnsTheEmployeesActivePenaltyWithItsDateAndDeduction() {
        ZoneId employeeZone = ZoneId.of("Pacific/Kiritimati");
        Employee employee = Employee.builder().userId(UUID.randomUUID()).build();
        when(employeeRepository.findByUser_Email(EMAIL)).thenReturn(Optional.of(employee));
        when(attendanceRulesService.resolveEmployeeZoneId(employee)).thenReturn(employeeZone);

        LocalDate today = LocalDate.now(employeeZone);
        AttendancePenalty penalty = AttendancePenalty.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employee.getUserId())
                .incidentDate(today.minusDays(6))
                .discrepancyType("LATE_ARRIVAL")
                .status(AttendancePenaltyStatus.PENDING_REVIEW)
                .deductionDays(new BigDecimal("0.5"))
                .evaluatedAt(LocalDateTime.now())
                .penalizedOn(LocalDateTime.now())
                .build();
        when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetweenAndStatus(
                eq(employee.getUserId()), eq(today.minusDays(89)), eq(today), eq(AttendancePenaltyStatus.PENDING_REVIEW)))
                .thenReturn(List.of(penalty));

        AttendanceDataProviders.MyPenalties provider = new AttendanceDataProviders.MyPenalties(
                attendancePenaltyRepository, employeeRepository, attendanceRulesService);

        Optional<String> result = provider.fetch(context());

        assertThat(result).isPresent();
        assertThat(result.get()).contains(today.minusDays(6).toString());
        assertThat(result.get()).contains("LATE_ARRIVAL");
        assertThat(result.get()).contains("0.5 day(s) deducted");
    }

    @Test
    void myPenaltiesIsEmptyWhenNoEmployeeProfileExists() {
        when(employeeRepository.findByUser_Email(EMAIL)).thenReturn(Optional.empty());

        AttendanceDataProviders.MyPenalties provider = new AttendanceDataProviders.MyPenalties(
                attendancePenaltyRepository, employeeRepository, attendanceRulesService);

        assertThat(provider.fetch(context())).isEmpty();
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
