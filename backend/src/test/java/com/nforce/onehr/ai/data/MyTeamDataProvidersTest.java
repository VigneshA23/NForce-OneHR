package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.dto.assignments.EmployeeAssignmentRow;
import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.PunctualityLeaderboardEntry;
import com.nforce.onehr.dto.attendance.TeamEffortEntry;
import com.nforce.onehr.dto.attendance.TeamNegligenceResponse;
import com.nforce.onehr.dto.attendance.TeamPunctualityResponse;
import com.nforce.onehr.dto.reports.AttendanceRequestReportRow;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendanceException;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.service.AttendanceRequestService;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import com.nforce.onehr.service.DirectReportScopeService;
import com.nforce.onehr.service.EmployeeAssignmentService;
import com.nforce.onehr.service.LeaveService;
import com.nforce.onehr.service.ReportsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The My Team AI providers: ranking (including ties) computed here rather than left to the model,
 * date-range extraction from the raw question, and - the point of the whole feature - every read
 * going through the caller's own never-widening direct-report scope
 * (ONEHR - NORA My Team access control).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyTeamDataProvidersTest {

    private static final String EMAIL = "manager@nforceone.com";
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Mock private AttendanceService attendanceService;
    @Mock private EmployeeAssignmentService employeeAssignmentService;
    @Mock private ReportsService reportsService;
    @Mock private AttendanceRequestService attendanceRequestService;
    @Mock private LeaveService leaveService;
    @Mock private DirectReportScopeService directReportScopeService;
    @Mock private AttendanceRulesService attendanceRulesService;
    @Mock private com.nforce.onehr.service.WorkingDayService workingDayService;
    @Mock private com.nforce.onehr.repository.AttendanceRepository attendanceRepository;
    @Mock private com.nforce.onehr.repository.AttendanceExceptionRepository attendanceExceptionRepository;

    private LocalDate today;

    @BeforeEach
    void setUp() {
        when(attendanceRulesService.getDefaultZoneId()).thenReturn(ZONE);
        today = LocalDate.now(ZONE);
    }

    private AssistantRequestContext as(ShellRole role, AudienceBucket... audiences) {
        return AssistantRequestContext.builder()
                .userId(UUID.randomUUID()).actorEmail(EMAIL).primaryRoleCode(role.name())
                .shellRole(role).audiences(Set.of(audiences)).build();
    }

    // ---------------------------------------------------------------- MyTeamEffort

    @Test
    void effort_ranksHighestAndLowestAvgHoursPerDay_andListsEveryone() {
        when(attendanceService.getTeamEffort(eq(EMAIL), any(), any())).thenReturn(List.of(
                effort("Asha", 9.0), effort("Bala", 6.0), effort("Chitra", 7.5)));
        when(attendanceService.getTeamPunctuality(eq(EMAIL), any(), any()))
                .thenReturn(TeamPunctualityResponse.builder().leaderboard(List.of()).daily(List.of())
                        .summary(summary(0, 0, 0)).build());

        String out = new MyTeamDataProviders.MyTeamEffort(attendanceService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE), "who worked the most hours")
                .orElseThrow();

        assertThat(out).contains("Most hours worked (highest avg. hrs/day): Asha (9.0 hrs/day)");
        assertThat(out).contains("Least hours worked (lowest avg. hrs/day): Bala (6.0 hrs/day)");
        assertThat(out).contains("Asha").contains("Bala").contains("Chitra");
    }

    @Test
    void effort_reportsATieAsBothNamesTogether_neverPicksOneArbitrarily() {
        when(attendanceService.getTeamEffort(eq(EMAIL), any(), any())).thenReturn(List.of(
                effort("Asha", 8.0), effort("Bala", 8.0)));
        when(attendanceService.getTeamPunctuality(eq(EMAIL), any(), any()))
                .thenReturn(TeamPunctualityResponse.builder().leaderboard(List.of()).daily(List.of())
                        .summary(summary(0, 0, 0)).build());

        String out = new MyTeamDataProviders.MyTeamEffort(attendanceService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE), "who worked the most hours")
                .orElseThrow();

        assertThat(out).contains("Most hours worked (highest avg. hrs/day): Asha (8.0 hrs/day) and Bala (8.0 hrs/day) (tied)");
        assertThat(out).contains("Least hours worked (lowest avg. hrs/day): Asha (8.0 hrs/day) and Bala (8.0 hrs/day) (tied)");
    }

    @Test
    void effort_onTimeLeaderboard_ranksHighestAndLowestPercentage_withTies() {
        when(attendanceService.getTeamEffort(eq(EMAIL), any(), any())).thenReturn(List.of());
        when(attendanceService.getTeamPunctuality(eq(EMAIL), any(), any())).thenReturn(TeamPunctualityResponse.builder()
                .leaderboard(List.of(punctuality("Asha", 10, 10, 100.0), punctuality("Bala", 10, 10, 100.0),
                        punctuality("Chitra", 5, 10, 50.0)))
                .daily(List.of())
                .summary(summary(8.3, 5, 10))
                .build());

        String out = new MyTeamDataProviders.MyTeamEffort(attendanceService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE),
                        "who from my team was on time the most").orElseThrow();

        assertThat(out).contains("Highest on-time percentage: Asha at 100.0% (10/10 days) and Bala at 100.0% (10/10 days) (tied)");
        assertThat(out).contains("Lowest on-time percentage: Chitra at 50.0% (5/10 days)");
        assertThat(out).contains("avg. 8.3, min 5, max 10");
    }

    @Test
    void effort_noDataMessage_whenNeitherLeaderboardHasAnything() {
        when(attendanceService.getTeamEffort(eq(EMAIL), any(), any())).thenReturn(List.of());
        when(attendanceService.getTeamPunctuality(eq(EMAIL), any(), any()))
                .thenReturn(TeamPunctualityResponse.builder().leaderboard(List.of()).daily(List.of())
                        .summary(summary(0, 0, 0)).build());

        String out = new MyTeamDataProviders.MyTeamEffort(attendanceService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("No direct-report attendance data is available");
    }

    @Test
    void effort_resolvesADateRangeNamedInTheQuestion_andPassesItToTheService() {
        when(attendanceService.getTeamEffort(any(), any(), any())).thenReturn(List.of());
        when(attendanceService.getTeamPunctuality(any(), any(), any()))
                .thenReturn(TeamPunctualityResponse.builder().leaderboard(List.of()).daily(List.of())
                        .summary(summary(0, 0, 0)).build());

        new MyTeamDataProviders.MyTeamEffort(attendanceService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE), "team effort this month");

        verify(attendanceService).getTeamEffort(EMAIL, today.withDayOfMonth(1), today);
    }

    @Test
    void effort_isRunnableByHrAdminAndSuperAdminToo_notJustManager() {
        MyTeamDataProviders.MyTeamEffort provider = new MyTeamDataProviders.MyTeamEffort(attendanceService, attendanceRulesService);
        assertThat(provider.audiences()).containsExactlyInAnyOrder(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);
        assertThat(provider.audiences()).doesNotContain(AudienceBucket.EMPLOYEE);
    }

    // ---------------------------------------------------------------- MyTeamNegligence

    @Test
    void negligence_ranksLateArrivals_leastHours_andFrequentBreaks_withTies() {
        when(attendanceService.getTeamNegligence(eq(EMAIL), any(), any())).thenReturn(TeamNegligenceResponse.builder()
                .lateArrivals(List.of(lateArrival("Asha", 4, 10, 40.0), lateArrival("Bala", 1, 10, 10.0)))
                .dailyLateCounts(List.of())
                .leastHoursWorked(List.of(leastHours("Bala", 5.0), leastHours("Asha", 5.0)))
                .hoursHistogram(List.of())
                .frequentBreaks(List.of(breaks("Chitra", 3.0, 6, 1.0), breaks("Deepa", 3.0, 4, 0.6)))
                .breaksTrend(List.of())
                .build());

        String out = new MyTeamDataProviders.MyTeamNegligence(attendanceService, attendanceRulesService)
                .fetch(as(ShellRole.HR_ADMIN, AudienceBucket.HR, AudienceBucket.EMPLOYEE), "who was late the most this week")
                .orElseThrow();

        assertThat(out).contains("Most late arrivals (highest late %): Asha at 40.0% (4/10 days)");
        assertThat(out).contains("Fewest late arrivals (lowest late %): Bala at 10.0% (1/10 days)");
        assertThat(out).contains("Lowest avg. hrs/day: Bala (5.0 hrs/day) and Asha (5.0 hrs/day) (tied)");
        assertThat(out).contains("Most break time: Chitra (3.0 hrs in 6 break(s)) and Deepa (3.0 hrs in 4 break(s)) (tied)");
        assertThat(out).contains("Highest avg. breaks/day: Chitra (3.0 hrs in 6 break(s))");
    }

    @Test
    void negligence_zeroBreakEmployeesAreNeverShownAsZero_theyAreSimplyAbsent() {
        when(attendanceService.getTeamNegligence(eq(EMAIL), any(), any())).thenReturn(TeamNegligenceResponse.builder()
                .lateArrivals(List.of(lateArrival("Asha", 1, 10, 10.0)))
                .dailyLateCounts(List.of()).leastHoursWorked(List.of()).hoursHistogram(List.of())
                .frequentBreaks(List.of()).breaksTrend(List.of())
                .build());

        String out = new MyTeamDataProviders.MyTeamNegligence(attendanceService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).doesNotContain("Frequent Breaks");
        assertThat(out).doesNotContain("Least Hours Worked");
    }

    @Test
    void negligence_noDataMessage_whenNothingAtAll() {
        when(attendanceService.getTeamNegligence(eq(EMAIL), any(), any())).thenReturn(TeamNegligenceResponse.builder()
                .lateArrivals(List.of()).dailyLateCounts(List.of()).leastHoursWorked(List.of())
                .hoursHistogram(List.of()).frequentBreaks(List.of()).breaksTrend(List.of()).build());

        String out = new MyTeamDataProviders.MyTeamNegligence(attendanceService, attendanceRulesService)
                .fetch(as(ShellRole.SUPER_ADMIN, AudienceBucket.ADMIN, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("No direct-report attendance data is available");
    }

    // ---------------------------------------------------------------- MyTeamAssignments

    @Test
    void assignments_listsEveryReport_andGroupsByShift() {
        when(employeeAssignmentService.listTeamAssignments(EMAIL, null, null, null, null, null, null)).thenReturn(List.of(
                assignment("Asha", "Morning", LocalTime.of(9, 0), LocalTime.of(18, 0), null, null),
                assignment("Bala", "Night", LocalTime.of(21, 0), LocalTime.of(6, 0), UUID.randomUUID(), "Morning")));

        String out = new MyTeamDataProviders.MyTeamAssignments(employeeAssignmentService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("Asha").contains("Bala");
        assertThat(out).contains("By shift:").contains("Morning (1): Asha").contains("Night (1): Bala");
        assertThat(out).contains("Scheduled future shift changes (1): Bala to Morning");
    }

    @Test
    void assignments_noDirectReports_saysSoPlainly() {
        when(employeeAssignmentService.listTeamAssignments(EMAIL, null, null, null, null, null, null)).thenReturn(List.of());

        String out = new MyTeamDataProviders.MyTeamAssignments(employeeAssignmentService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("no direct reports");
    }

    // ---------------------------------------------------------------- MyTeamRequestReports

    @Test
    void requestReports_neverCallsTheWideningEntryPoint_onlyTheDirectReportOnlyOne() {
        for (ReportsService.ReportType type : ReportsService.ReportType.values()) {
            when(reportsService.getAttendanceRequestReportForDirectReports(eq(EMAIL), eq(type), any(), any()))
                    .thenReturn(List.of());
        }

        new MyTeamDataProviders.MyTeamRequestReports(reportsService, attendanceRulesService)
                .fetch(as(ShellRole.HR_ADMIN, AudienceBucket.HR, AudienceBucket.EMPLOYEE), "regularization requests for my team");

        for (ReportsService.ReportType type : ReportsService.ReportType.values()) {
            verify(reportsService).getAttendanceRequestReportForDirectReports(eq(EMAIL), eq(type), any(), any());
        }
        verify(reportsService, never()).getAttendanceRequestReport(any(), any(), any(), any());
    }

    @Test
    void requestReports_countsPendingAndListsRows_perType() {
        when(reportsService.getAttendanceRequestReportForDirectReports(eq(EMAIL), eq(ReportsService.ReportType.OVERTIME), any(), any()))
                .thenReturn(List.of(requestRow("Asha", "SUBMITTED"), requestRow("Bala", "APPROVED")));
        for (ReportsService.ReportType type : ReportsService.ReportType.values()) {
            if (type == ReportsService.ReportType.OVERTIME) continue;
            when(reportsService.getAttendanceRequestReportForDirectReports(eq(EMAIL), eq(type), any(), any())).thenReturn(List.of());
        }

        String out = new MyTeamDataProviders.MyTeamRequestReports(reportsService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE), "overtime requests for my team")
                .orElseThrow();

        assertThat(out).contains("Overtime - exactly 2 request(s), 1 still pending:");
        assertThat(out).contains("Asha").contains("Bala");
    }

    @Test
    void requestReports_noDataAcrossEveryType_saysSoWithoutNamingAnyone() {
        for (ReportsService.ReportType type : ReportsService.ReportType.values()) {
            when(reportsService.getAttendanceRequestReportForDirectReports(eq(EMAIL), eq(type), any(), any())).thenReturn(List.of());
        }

        String out = new MyTeamDataProviders.MyTeamRequestReports(reportsService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("No direct-report requests");
    }

    // ---------------------------------------------------------------- MyTeamOverviewExtras

    @Test
    void overview_wfhTodayAndLeaveBalances_namesJoinedFromTheDirectReportScopeService() {
        UUID ashaId = UUID.randomUUID();
        when(directReportScopeService.directReportIds(EMAIL)).thenReturn(Set.of(ashaId));
        when(attendanceRequestService.listTeamApprovedWfh(EMAIL, today, today))
                .thenReturn(List.of(wfh("Asha", "FIRST_HALF")));
        when(leaveService.listTeamBalances(EMAIL)).thenReturn(List.of(
                LeaveBalanceResponse.builder().employeeUserId(ashaId).leaveTypeName("Casual Leave")
                        .totalDays(new java.math.BigDecimal("12")).usedDays(new java.math.BigDecimal("2"))
                        .remainingDays(new java.math.BigDecimal("10")).build()));
        when(directReportScopeService.directReports(EMAIL)).thenReturn(List.of(
                Employee.builder().userId(ashaId).fullName("Asha").build()));

        String out = new MyTeamDataProviders.MyTeamOverviewExtras(
                attendanceRequestService, leaveService, directReportScopeService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("Working from home today").contains("Asha (first half)");
        assertThat(out).contains("Asha: Casual Leave - 10 remaining of 12 (used 2)");
    }

    @Test
    void overview_nobodyOnWfh_saysSoInsteadOfDroppingTheBlock() {
        when(directReportScopeService.directReportIds(EMAIL)).thenReturn(Set.of(UUID.randomUUID()));
        when(attendanceRequestService.listTeamApprovedWfh(EMAIL, today, today)).thenReturn(List.of());
        when(leaveService.listTeamBalances(EMAIL)).thenReturn(List.of());

        Optional<String> out = new MyTeamDataProviders.MyTeamOverviewExtras(
                attendanceRequestService, leaveService, directReportScopeService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE));

        assertThat(out).hasValueSatisfying(text -> assertThat(text).contains("approved (0): none"));
    }

    @Test
    void overview_noDirectReports_returnsNoSection() {
        when(directReportScopeService.directReportIds(EMAIL)).thenReturn(Set.of());

        assertThat(new MyTeamDataProviders.MyTeamOverviewExtras(
                attendanceRequestService, leaveService, directReportScopeService, attendanceRulesService)
                .fetch(as(ShellRole.HR_ADMIN, AudienceBucket.HR, AudienceBucket.EMPLOYEE))).isEmpty();
    }

    // ---------------------------------------------------------------- MyTeamAttendanceDiscrepancies

    @Test
    void discrepancies_countAbsenceMissedPunchAndRepeatedLatenessPerPerson() {
        UUID ashaId = UUID.randomUUID(), raviId = UUID.randomUUID();
        LocalDate d1 = today.minusDays(1), d2 = today.minusDays(2), d3 = today.minusDays(3);
        List<Employee> team = List.of(Employee.builder().userId(ashaId).fullName("Asha").build(),
                Employee.builder().userId(raviId).fullName("Ravi").build());
        when(directReportScopeService.directReports(EMAIL)).thenReturn(team);
        when(workingDayService.computeExpectedWorkingDaysBulk(eq(team), any(), any())).thenReturn(java.util.Map.of(
                ashaId, WorkingDaySchedule.builder().employeeUserId(ashaId).workingDates(Set.of(d1, d2, d3)).build(),
                raviId, WorkingDaySchedule.builder().employeeUserId(raviId).workingDates(Set.of(d1, d2, d3)).build()));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(eq(List.of(ashaId, raviId)), any(), any())).thenReturn(List.of(
                // Asha: late twice, absent on d1
                Attendance.builder().employeeUserId(ashaId).workDate(d2).status("LATE")
                        .checkInAt(d2.atTime(9, 30)).checkOutAt(d2.atTime(18, 0)).build(),
                Attendance.builder().employeeUserId(ashaId).workDate(d3).status("LATE")
                        .checkInAt(d3.atTime(9, 30)).checkOutAt(d3.atTime(18, 0)).build(),
                // Ravi: every day punched, one never checked out
                Attendance.builder().employeeUserId(raviId).workDate(d1).status("PRESENT").checkInAt(d1.atTime(9, 0)).build(),
                Attendance.builder().employeeUserId(raviId).workDate(d2).status("PRESENT")
                        .checkInAt(d2.atTime(9, 0)).checkOutAt(d2.atTime(18, 0)).build(),
                Attendance.builder().employeeUserId(raviId).workDate(d3).status("PRESENT")
                        .checkInAt(d3.atTime(9, 0)).checkOutAt(d3.atTime(18, 0)).build()));
        when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                eq(List.of(ashaId, raviId)), any(), any())).thenReturn(List.of(
                AttendanceException.builder().employeeUserId(raviId).exceptionDate(d2).exceptionType("EARLY_DEPARTURE").build()));

        String out = new MyTeamDataProviders.MyTeamAttendanceDiscrepancies(directReportScopeService,
                attendanceRepository, attendanceExceptionRepository, workingDayService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER), "who was absent or late in my team in the last 7 days").orElseThrow();

        assertThat(out).contains("started) - exactly 1 day(s) across 1 of 2 direct report(s)")
                .contains("Asha 1 (" + d1 + " (yesterday))");
        assertThat(out).contains("Late arrivals (status LATE) - exactly 2 day(s) across 1 of 2")
                .contains("Repeated late arrivals - more than once (1): Asha 2");
        assertThat(out).contains("Missed punches (checked in but never checked out) - exactly 1 day(s)")
                .contains("- Most missed punches: Ravi with 1");
        assertThat(out).contains("Left before the shift ended - exactly 1 day(s) across 1 of 2");
    }

    // ---------------------------------------------------------------- helpers

    private static TeamEffortEntry effort(String name, double avgHours) {
        return TeamEffortEntry.builder().employeeUserId(UUID.randomUUID()).fullName(name)
                .avgHoursPerDay(avgHours).hoursWorked(avgHours * 5).expectedHours(40).activeDays(5).build();
    }

    private static PunctualityLeaderboardEntry punctuality(String name, int onTime, int expected, double pct) {
        return PunctualityLeaderboardEntry.builder().employeeUserId(UUID.randomUUID()).fullName(name)
                .onTimeDays(onTime).expectedWorkingDays(expected).percentage(pct).build();
    }

    private static com.nforce.onehr.dto.attendance.PunctualitySummary summary(double avg, int min, int max) {
        return com.nforce.onehr.dto.attendance.PunctualitySummary.builder()
                .averageEmployeesOnTime(avg).minimumEmployeesOnTime(min).maximumEmployeesOnTime(max).build();
    }

    private static TeamNegligenceResponse.LateArrivalEntry lateArrival(String name, int lateDays, int activeDays, double pct) {
        return TeamNegligenceResponse.LateArrivalEntry.builder().employeeUserId(UUID.randomUUID()).fullName(name)
                .lateDays(lateDays).activeDays(activeDays).latePct(pct).build();
    }

    private static TeamNegligenceResponse.LeastHoursEntry leastHours(String name, double avgHours) {
        return TeamNegligenceResponse.LeastHoursEntry.builder().employeeUserId(UUID.randomUUID()).fullName(name)
                .avgHoursPerDay(avgHours).hoursWorked(avgHours * 5).build();
    }

    private static TeamNegligenceResponse.FrequentBreaksEntry breaks(String name, double hours, int count, double avgPerDay) {
        return TeamNegligenceResponse.FrequentBreaksEntry.builder().employeeUserId(UUID.randomUUID()).fullName(name)
                .totalBreakHours(hours).totalBreakCount(count).avgBreaksPerDay(avgPerDay).build();
    }

    private static EmployeeAssignmentRow assignment(String name, String shiftName, LocalTime start, LocalTime end,
                                                      UUID pendingShiftId, String pendingShiftName) {
        return EmployeeAssignmentRow.builder().employeeUserId(UUID.randomUUID()).employeeCode("NF-1").fullName(name)
                .shiftName(shiftName).shiftStartTime(start).shiftEndTime(end)
                .pendingShiftId(pendingShiftId).pendingShiftName(pendingShiftName)
                .pendingShiftEffectiveFrom(pendingShiftId == null ? null : LocalDate.of(2026, 10, 1))
                .build();
    }

    private static AttendanceRequestReportRow requestRow(String name, String status) {
        return AttendanceRequestReportRow.builder().employeeUserId(UUID.randomUUID()).fullName(name)
                .date(LocalDate.of(2026, 9, 10)).status(status).build();
    }

    private static AttendanceRequestResponse wfh(String name, String mode) {
        return AttendanceRequestResponse.builder().employeeUserId(UUID.randomUUID()).employeeName(name)
                .requestType("WFH").partialDayMode(mode).status("APPROVED").build();
    }
}
