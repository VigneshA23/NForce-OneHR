package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
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
        assertThat(from.getValue()).isEqualTo(expectedToday.minusDays(7));
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
