package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.ai.contract.ShellRole;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import com.nforce.onehr.service.EmployeeService;
import com.nforce.onehr.service.LeaveService;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Providers whose rows depend on what the question names: a person, a period, a status. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionAwareProvidersTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final String EMAIL = "praveen.g@nforce.test";

    @Mock private AttendanceRulesService attendanceRulesService;
    @Mock private AttendanceService attendanceService;
    @Mock private com.nforce.onehr.service.AttendanceRequestService attendanceRequestService;
    @Mock private EmployeeService employeeService;
    @Mock private LeaveService leaveService;
    @Mock private com.nforce.onehr.repository.EmployeeRepository employeeRepository;
    @Mock private com.nforce.onehr.repository.AttendancePenaltyRepository attendancePenaltyRepository;

    private LocalDate today;

    @BeforeEach
    void zone() {
        when(attendanceRulesService.getDefaultZoneId()).thenReturn(ZONE);
        today = LocalDate.now(ZONE);
    }

    private static AssistantRequestContext as(ShellRole role, AudienceBucket... audiences) {
        return AssistantRequestContext.builder().userId(UUID.randomUUID()).actorEmail(EMAIL)
                .primaryRoleCode(role.name()).shellRole(role).audiences(Set.of(audiences)).build();
    }

    private static DirectoryEntryDto person(String name, String email, String dept, String manager) {
        return DirectoryEntryDto.builder().userId(UUID.randomUUID().toString()).fullName(name).email(email)
                .employeeCode("E-" + name.length()).departmentName(dept).designationName("Engineer")
                .managerName(manager).active(true).build();
    }

    // ---------------------------------------------------------------- people named in the question

    private final DirectoryEntryDto me = person("PRAVEEN GURRAM", EMAIL, "Quality Engineering", "Ramesh");
    private final DirectoryEntryDto other = person("Praveen Kumar", "praveen.k@nforce.test", "Finance", "Suresh");
    private final DirectoryEntryDto ramesh = person("Ramesh Rao", "ramesh@nforce.test", "Quality Engineering", null);

    @Test
    @DisplayName("a first name shared by two people lists both, with the caller marked")
    void sharedFirstNameListsEveryone() {
        when(employeeService.listDirectory()).thenReturn(List.of(me, other, ramesh));

        String out = new PeopleDataProviders.NamedInQuestion(employeeService)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE), "Tell me about Praveen").orElseThrow();

        assertThat(out).contains("exactly 2").contains("PRAVEEN GURRAM (this is you, the signed-in user)")
                .contains("Praveen Kumar").doesNotContain("Ramesh Rao").doesNotContain("@");
    }

    @Test
    @DisplayName("an initial narrows to the one person it fits")
    void initialNarrows() {
        assertThat(PeopleDataProviders.NamedInQuestion.matches("Show details for Praveen G.", List.of(me, other, ramesh)))
                .containsExactly(me);
        assertThat(PeopleDataProviders.NamedInQuestion.matches("tell me about praveen gurram", List.of(me, other)))
                .containsExactly(me);
    }

    @Test
    @DisplayName("a second person named alongside is kept, so a reporting manager can be correlated")
    void managerNamedAlongside() {
        assertThat(PeopleDataProviders.NamedInQuestion.matches(
                "Was the Praveen reporting to Manager Ramesh present yesterday?", List.of(me, other, ramesh)))
                .containsExactlyInAnyOrder(me, other, ramesh);
    }

    @Test
    @DisplayName("no name in the question means no block at all")
    void noNameNoBlock() {
        when(employeeService.listDirectory()).thenReturn(List.of(me, other));
        assertThat(new PeopleDataProviders.NamedInQuestion(employeeService)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE), "what is my leave balance")).isEmpty();
    }

    // ---------------------------------------------------------------- leave requests

    private static LeaveRequestResponse request(LocalDate from, LocalDate to, String status, String reason) {
        return LeaveRequestResponse.builder().leaveTypeName("Casual Leave").startDate(from).endDate(to)
                .totalDays(BigDecimal.ONE).status(status).decidedByName("Ramesh").decisionReason(reason).build();
    }

    @Test
    @DisplayName("rejected requests in a named period are listed with their reason")
    void rejectedInPeriod() {
        when(leaveService.listMyRequests(EMAIL)).thenReturn(List.of(
                request(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 9, 22), "REJECTED", "Release week"),
                request(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 24), "APPROVED", null),
                request(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 3), "REJECTED", "Too short notice")));

        String out = new LeaveDataProviders.MyRequests(leaveService, attendanceRulesService)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE),
                        "Show my rejected leave requests for 21-09-2026 to 28-09-2026.").orElseThrow();

        assertThat(out).contains("status REJECTED, overlapping 2026-09-21 to 2026-09-28")
                .contains("2026-09-22").contains("reason given: Release week")
                .doesNotContain("2026-09-24 to").doesNotContain("Too short notice");
    }

    @Test
    @DisplayName("no matching request is stated as none, never left unsaid")
    void noneIsStated() {
        when(leaveService.listMyRequests(EMAIL)).thenReturn(List.of(
                request(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 24), "APPROVED", null)));

        String out = new LeaveDataProviders.MyRequests(leaveService, attendanceRulesService)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE), "why was my leave on 03-09-2026 rejected").orElseThrow();

        assertThat(out).contains("none - every request was checked");
    }

    @Test
    @DisplayName("a balance reduced by a penalty says so, and says no leave was taken")
    void balanceDecreaseFromPenaltyIsAttributed() {
        // ONEHR - "why did my leave balance decrease" was answered as 1 day of Annual Leave used,
        // for an employee with no leave request whose day was deducted by a penalty.
        UUID userId = UUID.randomUUID();
        when(employeeRepository.findByUser_Email(EMAIL))
                .thenReturn(java.util.Optional.of(com.nforce.onehr.entity.Employee.builder().userId(userId).build()));
        when(leaveService.listMyBalances(EMAIL)).thenReturn(List.of(com.nforce.onehr.dto.LeaveBalanceResponse.builder()
                .leaveTypeName("Annual Leave").totalDays(new BigDecimal("15")).usedDays(BigDecimal.ONE)
                .remainingDays(new BigDecimal("14")).build()));
        when(leaveService.listMyRequests(EMAIL)).thenReturn(List.of());
        com.nforce.onehr.entity.AttendancePenalty penalty = new com.nforce.onehr.entity.AttendancePenalty();
        penalty.setIncidentDate(today.withDayOfMonth(1));
        penalty.setDiscrepancyType("MISSING_CHECKOUT");
        penalty.setLeaveDeductionDays(new BigDecimal("1.00"));
        penalty.setLeaveBreakdown("{\"AL\":1.00}");
        when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetweenAndStatus(
                userId, LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear(), 12, 31),
                com.nforce.onehr.entity.AttendancePenaltyStatus.PENDING_REVIEW)).thenReturn(List.of(penalty));

        String out = new LeaveDataProviders.Balances(leaveService, employeeRepository, attendancePenaltyRepository, attendanceRulesService)
                .fetch(as(ShellRole.EMPLOYEE, AudienceBucket.EMPLOYEE)).orElseThrow();

        assertThat(out).contains("Annual Leave: 14 of 15 days remaining (1 used)")
                .contains("exactly 1, 1 day(s) in total")
                .contains(today.withDayOfMonth(1) + ": MISSING_CHECKOUT penalty - 1 day(s) deducted from leave (AL 1.00)")
                .contains("Approved leave requests in %d: none - no used days come from leave taken".formatted(today.getYear()));
    }

    // ---------------------------------------------------------------- organisation attendance

    @Test
    @DisplayName("\"absent yesterday\" reads yesterday's roster, counted per department")
    void orgAbsentYesterdayByDepartment() {
        LocalDate yesterday = today.minusDays(1);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        when(attendanceService.getDayForAll(yesterday)).thenReturn(List.of(
                AttendanceResponse.builder().employeeUserId(a).fullName("Asha").checkInAt(yesterday.atTime(9, 0)).status("PRESENT").build(),
                AttendanceResponse.builder().employeeUserId(b).fullName("Bala").build(),
                AttendanceResponse.builder().employeeUserId(c).fullName("Chitra").build()));
        when(employeeService.listDirectory()).thenReturn(List.of(
                DirectoryEntryDto.builder().userId(a.toString()).departmentName("Quality Engineering").active(true).build(),
                DirectoryEntryDto.builder().userId(b.toString()).departmentName("Quality Engineering").active(true).build(),
                DirectoryEntryDto.builder().userId(c.toString()).departmentName("Finance").active(true).build()));

        String out = new OrganisationDataProviders.OrgAttendanceToday(attendanceService, attendanceRulesService, employeeService, attendanceRequestService)
                .fetch(as(ShellRole.SUPER_ADMIN, AudienceBucket.ADMIN),
                        "How many employees in Quality Engineering were absent yesterday?").orElseThrow();

        assertThat(out).contains(yesterday + " (yesterday)")
                .contains("Quality Engineering (2 active): 1 checked in, 0 on leave, 1 absent (no check-in, not on leave) - Bala")
                .contains("Finance (1 active): 0 checked in, 0 on leave, 1 absent");
    }
}
