package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.dto.ApprovalItemDto;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.HolidayResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.dto.ManagerDashboardDto;
import com.nforce.onehr.dto.PunchResponse;
import com.nforce.onehr.dto.attendance.AttendanceConfigResponse;
import com.nforce.onehr.dto.attendance.AttendancePenaltyResponse;
import com.nforce.onehr.dto.expense.ExpenseClaimResponse;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.ApprovalCenterService;
import com.nforce.onehr.service.AttendancePenaltyService;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import com.nforce.onehr.service.EmployeeService;
import com.nforce.onehr.service.ExpenseService;
import com.nforce.onehr.service.HolidayService;
import com.nforce.onehr.service.LeaveService;
import com.nforce.onehr.service.UserManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the live-data providers added so every role can ask about what its own screens
 * show - and of the fixes to the older ones. Each test pins a figure to the way the matching screen
 * computes it, because "the assistant says 41 and the dashboard says 42" is the failure these exist
 * to prevent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiveDataProvidersTest {

    private static final String EMAIL = "someone@nforceone.com";
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Mock private AttendanceRulesService attendanceRulesService;
    @Mock private AttendanceService attendanceService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserManagementService userManagementService;
    @Mock private EmployeeService employeeService;
    @Mock private LeaveService leaveService;
    @Mock private AttendancePenaltyService attendancePenaltyService;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private HolidayService holidayService;
    @Mock private ApprovalCenterService approvalCenterService;
    @Mock private ExpenseService expenseService;

    private LocalDate today;

    @BeforeEach
    void zone() {
        when(attendanceRulesService.getDefaultZoneId()).thenReturn(ZONE);
        when(employeeRepository.findByUser_Email(anyString())).thenReturn(Optional.empty());
        today = LocalDate.now(ZONE);
    }

    private AssistantRequestContext as(ShellRole role, AudienceBucket... audiences) {
        return AssistantRequestContext.builder()
                .userId(UUID.randomUUID())
                .actorEmail(EMAIL)
                .primaryRoleCode(role.name())
                .shellRole(role)
                .audiences(Set.of(audiences))
                .build();
    }

    // ---------------------------------------------------------------- organisation

    @Test
    @DisplayName("user accounts: active and inactive counted exactly as User Management counts them")
    void userAccountsCountLikeUserManagement() {
        when(userManagementService.listUsers()).thenReturn(List.of(
                user("Asha", "EMPLOYEE", true, "Engineering", true, today.minusYears(1)),
                user("Bala", "EMPLOYEE", true, "Engineering", false, today.withDayOfMonth(1)),
                user("Chitra", "MANAGER", true, "Sales", true, today.minusYears(2)),
                user("Dev", "HR_ADMIN", false, "HR", true, today.minusYears(3))));

        String out = new OrganisationDataProviders.UserAccounts(userManagementService, attendanceRulesService)
                .fetch(as(ShellRole.SUPER_ADMIN, AudienceBucket.ADMIN, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("exactly 4 users: 3 active and 1 inactive");
        assertThat(out).contains("Active users by role: Employee 2, Manager 1");
        assertThat(out).contains("Active employees with no reporting manager (1): Bala");
        assertThat(out).contains("Joined this month").contains(": 1");
        assertThat(out).contains("Inactive users (1): Dev");
    }

    @Test
    @DisplayName("user accounts are Super Admin only, matching UserManagementController")
    void userAccountsAreSuperAdminOnly() {
        assertThat(new OrganisationDataProviders.UserAccounts(null, null).audiences())
                .containsExactly(AudienceBucket.ADMIN);
    }

    @Test
    @DisplayName("headcount: active employees and this month's joiners, from the HR dashboard's own read")
    void headcountFromOrgDashboard() {
        when(employeeService.getOrgDashboard()).thenReturn(ManagerDashboardDto.builder()
                .directReports(List.of(
                        report("Asha", "Engineering", true, "EMPLOYEE"),
                        report("Bala", "Engineering", true, "MANAGER"),
                        report("Chitra", "Sales", false, "EMPLOYEE")))
                .teamJoiners(List.of(
                        joiner("Bala", today.withDayOfMonth(1)),
                        joiner("Old", today.withDayOfMonth(1).minusMonths(3))))
                .build());

        String out = new OrganisationDataProviders.Headcount(employeeService, attendanceRulesService)
                .fetch(as(ShellRole.HR_ADMIN, AudienceBucket.HR, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("Exactly 3 employees on record: 2 active and 1 inactive");
        assertThat(out).contains("Active employees by department: Engineering 2");
        assertThat(out).contains("New joiners in the last 12 months: 2");
        assertThat(out).contains("(1): Bala on " + today.withDayOfMonth(1));
    }

    @Test
    @DisplayName("org attendance today: present means has a check-in, the Present Today tile's own rule")
    void orgAttendanceMatchesPresentTodayTile() {
        when(attendanceService.getDayForAll(today)).thenReturn(List.of(
                roster("Asha", today.atTime(9, 5), "PRESENT", 0),
                roster("Bala", today.atTime(10, 40), "LATE", 70),
                roster("Chitra", null, "ON_LEAVE", null),
                roster("Dev", null, null, null)));

        String out = new OrganisationDataProviders.OrgAttendanceToday(attendanceService, attendanceRulesService)
                .fetch(as(ShellRole.HR_ADMIN, AudienceBucket.HR, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("2 of 4 employees on the attendance roster have checked in");
        assertThat(out).contains("Late today (1): Bala (70 min past shift start)");
        assertThat(out).contains("On approved leave today, no punch (1): Chitra");
        assertThat(out).contains("Not checked in yet (1): Dev");
    }

    // ---------------------------------------------------------------- team

    @Test
    @DisplayName("team leave: somebody with two overlapping requests is one person on leave today")
    void teamLeaveCountsPeopleNotRequests() {
        UUID asha = UUID.randomUUID();
        when(leaveService.listTeamLeave(eq(EMAIL), eq(today), eq(today.plusDays(13)))).thenReturn(List.of(
                leave(asha, "Asha", today.minusDays(1), today.plusDays(1)),
                leave(asha, "Asha", today, today),
                leave(UUID.randomUUID(), "Bala", today.plusDays(5), today.plusDays(6))));

        String out = new TeamDataProviders.TeamLeave(leaveService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("On approved leave today (" + today + "): 1 of your direct reports");
        assertThat(out).contains("3 approved leave request(s):");
        assertThat(out).contains("Bala: Casual Leave, " + today.plusDays(5));
    }

    @Test
    @DisplayName("team penalties stand aside for HR, whose same service call returns the whole organisation")
    void teamPenaltiesDoNotMislabelOrgDataAsTeam() {
        Optional<String> out = new TeamDataProviders.TeamPenalties(attendancePenaltyService, attendanceRulesService)
                .fetch(as(ShellRole.HR_ADMIN, AudienceBucket.HR, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE));

        assertThat(out).isEmpty();
        verifyNoInteractions(attendancePenaltyService);
    }

    @Test
    @DisplayName("team penalties report only what the Attendance Log badges as PENALIZED")
    void teamPenaltiesAreActiveOnly() {
        when(attendancePenaltyService.list(eq(EMAIL), any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(
                penalty("Asha", today.minusDays(2), "PENDING_REVIEW"),
                penalty("Bala", today.minusDays(3), "CANCELLED")));

        String out = new TeamDataProviders.TeamPenalties(attendancePenaltyService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("Exactly 1 active (PENDING_REVIEW) penalty(ies)");
        assertThat(out).contains("Asha").doesNotContain("Bala");
    }

    // ---------------------------------------------------------------- peers

    @Test
    @DisplayName("peers: an employee's project team, today's check-ins and approved leave, as My Team shows it")
    void peerTeamForAnEmployee() {
        when(attendanceService.getDayForPeers(EMAIL, today)).thenReturn(List.of(
                roster("Me", today.atTime(9, 0), "PRESENT", 0),
                roster("Asha", null, "ON_LEAVE", null)));
        when(leaveService.listPeerLeave(EMAIL, today, today.plusDays(13))).thenReturn(List.of(
                leave(UUID.randomUUID(), "Asha", today, today.plusDays(1))));

        String out = new PeerDataProviders.PeerTeam(attendanceService, leaveService, attendanceRulesService)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("(2 people)");
        assertThat(out).contains("1 of 2 people in your project team have checked in");
        assertThat(out).contains("On approved leave today (" + today + "): 1 person(s) in your project team - Asha");
    }

    @Test
    @DisplayName("peers stand aside for a manager, to whom 'my team' means their direct reports")
    void peerTeamStandsAsideForManagers() {
        assertThat(new PeerDataProviders.PeerTeam(attendanceService, leaveService, attendanceRulesService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE))).isEmpty();
        verifyNoInteractions(leaveService);
    }

    // ---------------------------------------------------------------- self

    @Test
    @DisplayName("history: late only past the grace period, phrased like the Attendance page's badge")
    void historyLatenessIsGraceAware() {
        when(attendanceService.currentWorkDate(EMAIL)).thenReturn(today);
        when(attendanceService.getConfig(EMAIL)).thenReturn(config(10));
        LocalDateTime shiftStart = today.minusDays(1).atTime(9, 30);
        when(attendanceService.getMyHistory(EMAIL, today.minusDays(29), today)).thenReturn(List.of(
                AttendanceResponse.builder().workDate(today).status("PRESENT").lateByMinutes(5)
                        .checkInAt(today.atTime(9, 35)).shiftStartAt(today.atTime(9, 30)).workedMinutes(480).build(),
                AttendanceResponse.builder().workDate(today.minusDays(1)).status("LATE").lateByMinutes(55)
                        .checkInAt(shiftStart.plusMinutes(55).plusSeconds(36)).shiftStartAt(shiftStart).workedMinutes(420).build()));

        String out = new AttendanceDataProviders.MyHistory(attendanceService, employeeRepository, attendancePenaltyRepository)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("exactly 2 day(s) with an attendance record");
        assertThat(out).contains("- LATE_ARRIVAL (1): " + today.minusDays(1));
        assertThat(out).contains("late by 55m 36s");
    }

    @Test
    @DisplayName("today: an open session counts toward hours worked and reads as clocked in")
    void todayCountsTheOpenSession() {
        when(attendanceService.currentWorkDate(EMAIL)).thenReturn(today);
        when(attendanceService.getConfig(EMAIL)).thenReturn(config(10));
        LocalDateTime checkIn = LocalDateTime.now(ZONE).minusMinutes(90);
        when(attendanceService.getPunchForDate(EMAIL, today)).thenReturn(AttendanceResponse.builder()
                .workDate(today).status("PRESENT").checkInAt(checkIn).workedMinutes(0).lateByMinutes(0).build());
        when(attendanceService.getPunches(EMAIL, today)).thenReturn(List.of(
                PunchResponse.builder().checkInAt(checkIn).build()));

        String out = new AttendanceDataProviders.Today(attendanceService, employeeRepository, attendanceRulesService)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("Currently clocked in: yes");
        assertThat(out).containsPattern("Worked so far today: 1h (29|30|31)m");
        assertThat(out).contains("Arrival: on time");
    }

    @Test
    @DisplayName("today never calls getToday, which settles a stale session as a side effect")
    void todayUsesPureReads() {
        when(attendanceService.currentWorkDate(EMAIL)).thenReturn(today);
        when(attendanceService.getPunches(EMAIL, today)).thenReturn(List.of());

        String out = new AttendanceDataProviders.Today(attendanceService, employeeRepository, attendanceRulesService)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("Not checked in yet today");
        verify(attendanceService, never()).getToday(anyString(), any());
    }

    @Test
    @DisplayName("holidays: the next one is named, and a cap never drops the soonest")
    void holidaysKeepTheSoonest() {
        List<HolidayResponse> many = new ArrayList<>();
        for (int i = 1; i <= 20; i++) many.add(holiday("Holiday " + i, today.plusDays(i * 7L)));
        many.add(holiday("Past", today.minusDays(10)));
        when(holidayService.getHolidaysForMyLocation(EMAIL)).thenReturn(many);

        String out = new HolidayDataProviders.UpcomingHolidays(holidayService, employeeRepository, attendanceRulesService)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("Next holiday: Holiday 1 on " + today.plusDays(7));
        assertThat(out).contains("20 upcoming holiday(s) in total; only the 15 soonest are listed");
        assertThat(out).contains(": Holiday 1").doesNotContain(": Holiday 16");
        assertThat(out).contains("Most recent past holiday: Past");
    }

    // ---------------------------------------------------------------- approvals and fixes

    @Test
    @DisplayName("approval total comes from the Approval Center's own queue, document reviews included")
    void approvalSummaryMatchesTheApprovalCenter() {
        when(approvalCenterService.pendingApprovals(EMAIL)).thenReturn(List.of(
                item("LEAVE"), item("LEAVE"), item("EXPENSE"), item("HELP_CONTENT")));

        String out = new ApprovalSummaryProvider(approvalCenterService)
                .fetch(as(ShellRole.HR_ADMIN, AudienceBucket.HR, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("4 total awaiting your decision");
        assertThat(out).contains("- Leave: 2").contains("- Expense: 1").contains("- Document Review");
    }

    @Test
    @DisplayName("HR's expense queue covers both stages, as the Approval Center shows it")
    void adminExpenseQueueUsesTheFinalApproverRead() {
        when(expenseService.pendingForFinalApprover(EMAIL)).thenReturn(List.of(
                claim("SUBMITTED", "100.00"), claim("MANAGER_APPROVED", "250.00")));

        String out = new ExpenseDataProviders.PendingForManager(expenseService)
                .fetch(as(ShellRole.HR_ADMIN, AudienceBucket.HR, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("2 awaiting your decision, totalling 350.00");
        assertThat(out).contains("1 still at the manager stage").contains("1 approved by a manager");
        verify(expenseService, never()).pendingForManager(anyString());
    }

    @Test
    @DisplayName("a manager's expense queue stays the manager-stage read")
    void managerExpenseQueueUsesTheManagerRead() {
        when(expenseService.pendingForManager(EMAIL)).thenReturn(List.of(claim("SUBMITTED", "100.00")));

        new ExpenseDataProviders.PendingForManager(expenseService)
                .fetch(as(ShellRole.MANAGER, AudienceBucket.MANAGER, AudienceBucket.EMPLOYEE));

        verify(expenseService, never()).pendingForFinalApprover(anyString());
    }

    @Test
    @DisplayName("an HR-stage rejection keeps its reason")
    void myClaimsKeepTheFinalStageReason() {
        ExpenseClaimResponse rejected = claim("FINAL_REJECTED", "90.00");
        rejected.setFinalDecidedByName("Priya HR");
        rejected.setFinalRejectionReason("Receipt is illegible");
        when(expenseService.myClaims(EMAIL)).thenReturn(List.of(rejected));

        String out = new ExpenseDataProviders.MyClaims(expenseService)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("final stage: Priya HR").contains("final approver's reason: Receipt is illegible");
    }

    @Test
    @DisplayName("a capped list states the true total, not only what it shows")
    void cappedListsStateTheTotal() {
        assertThat(LiveDataText.listHeader(12, 5, "leave request(s)", "most recent"))
                .startsWith("12 leave request(s) in total; only the 5 most recent are listed");
        assertThat(LiveDataText.listHeader(3, 5, "leave request(s)", "most recent")).isEqualTo("3 leave request(s):");
    }

    // ---------------------------------------------------------------- fixtures

    private static EmployeeResponse user(String name, String role, boolean active, String dept, boolean hasManager, LocalDate joined) {
        return EmployeeResponse.builder().userId(UUID.randomUUID()).fullName(name).role(role).active(active)
                .departmentName(dept).joiningDate(joined)
                .currentManager(hasManager ? EmployeeResponse.ManagerRef.builder().fullName("Boss").email("boss@x").build() : null)
                .build();
    }

    private static ManagerDashboardDto.DirectReport report(String name, String dept, boolean active, String role) {
        return ManagerDashboardDto.DirectReport.builder().fullName(name).departmentName(dept).active(active).roleCode(role).build();
    }

    private static ManagerDashboardDto.TeamJoiner joiner(String name, LocalDate on) {
        return ManagerDashboardDto.TeamJoiner.builder().fullName(name).joinedTeamOn(on.toString()).active(true).build();
    }

    private static AttendanceResponse roster(String name, LocalDateTime checkIn, String status, Integer lateBy) {
        return AttendanceResponse.builder().fullName(name).checkInAt(checkIn).status(status).lateByMinutes(lateBy).build();
    }

    private static LeaveRequestResponse leave(UUID who, String name, LocalDate from, LocalDate to) {
        return LeaveRequestResponse.builder().employeeUserId(who).employeeName(name).leaveTypeName("Casual Leave")
                .startDate(from).endDate(to).totalDays(BigDecimal.ONE).status("APPROVED").build();
    }

    private static AttendancePenaltyResponse penalty(String name, LocalDate on, String status) {
        return AttendancePenaltyResponse.builder().fullName(name).incidentDate(on).status(status)
                .discrepancyType("LATE_ARRIVAL").deductionDays(new BigDecimal("0.5")).build();
    }

    private static AttendanceConfigResponse config(int grace) {
        return AttendanceConfigResponse.builder().shiftName("General").shiftStart(LocalTime.of(9, 30))
                .shiftEnd(LocalTime.of(18, 30)).lateGraceMinutes(grace).halfDayMaxHours(4.0)
                .weeklyOffDays(List.of("SATURDAY", "SUNDAY")).build();
    }

    private static HolidayResponse holiday(String name, LocalDate on) {
        return HolidayResponse.builder().holidayName(name).holidayDate(on).locationName("Hyderabad").active(true).build();
    }

    private static ApprovalItemDto item(String type) {
        return ApprovalItemDto.builder().id(UUID.randomUUID().toString()).requestType(type).build();
    }

    private static ExpenseClaimResponse claim(String status, String amount) {
        return ExpenseClaimResponse.builder().id(UUID.randomUUID()).employeeName("Asha").categoryName("Travel")
                .amount(new BigDecimal(amount)).expenseDate(LocalDate.of(2026, 9, 1)).status(status).build();
    }
}
