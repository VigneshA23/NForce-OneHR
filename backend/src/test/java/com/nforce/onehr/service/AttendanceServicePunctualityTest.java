package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.DailyPunctuality;
import com.nforce.onehr.dto.attendance.PunctualityLeaderboardEntry;
import com.nforce.onehr.dto.attendance.TeamPunctualityResponse;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Team Punctuality / On-Time Leaderboard — "on time" is exactly {@code status == PRESENT}.
 * Same isolation approach as AttendanceServiceTeamStatsTest (pure Mockito).
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServicePunctualityTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private AttendancePunchRepository attendancePunchRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private AttendanceProperties props;
    @Mock private WorkingDayService workingDayService;

    @InjectMocks private AttendanceService attendanceService;

    private final UUID managerId = UUID.randomUUID();
    private final UUID emp1Id = UUID.randomUUID();
    private final UUID emp2Id = UUID.randomUUID();
    private final String managerEmail = "manager@test.com";

    private final LocalDate mon = LocalDate.of(2026, 8, 3);
    private final LocalDate tue = LocalDate.of(2026, 8, 4);
    private final LocalDate wed = LocalDate.of(2026, 8, 5);

    private Employee manager;
    private Employee emp1;
    private Employee emp2;

    @BeforeEach
    void setUp() {
        manager = Employee.builder().userId(managerId).fullName("Manager One").build();
        emp1 = Employee.builder().userId(emp1Id).fullName("Employee One").employeeCode("NF-1").build();
        emp2 = Employee.builder().userId(emp2Id).fullName("Employee Two").employeeCode("NF-2").build();

        when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(manager));
        lenient().when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(emp1Id, emp2Id));
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(List.of(emp1Id, emp2Id))).thenReturn(List.of(emp1, emp2));
    }

    private Attendance record(UUID employeeId, LocalDate date, String status) {
        return Attendance.builder().id(UUID.randomUUID()).employeeUserId(employeeId).workDate(date).status(status).build();
    }

    private void stubSchedules(Set<LocalDate> emp1Days, Set<LocalDate> emp2Days) {
        when(workingDayService.computeExpectedWorkingDaysBulk(List.of(emp1, emp2), mon, wed)).thenReturn(Map.of(
                emp1Id, WorkingDaySchedule.builder().employeeUserId(emp1Id).workingDates(emp1Days).build(),
                emp2Id, WorkingDaySchedule.builder().employeeUserId(emp2Id).workingDates(emp2Days).build()));
    }

    @Test
    void onlyPresentCountsAsOnTime_lateAndHalfDayAndAbsentDoNot() {
        stubSchedules(Set.of(mon, tue, wed), Set.of(mon, tue, wed));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), mon, wed)).thenReturn(List.of(
                record(emp1Id, mon, "PRESENT"),
                record(emp1Id, tue, "LATE"),
                record(emp1Id, wed, "HALF_DAY")
                // emp2 has zero attendance rows at all — ABSENT by omission.
        ));

        TeamPunctualityResponse result = attendanceService.getTeamPunctuality(managerEmail, mon, wed);

        PunctualityLeaderboardEntry emp1Entry = result.getLeaderboard().stream()
                .filter(e -> e.getEmployeeUserId().equals(emp1Id)).findFirst().orElseThrow();
        assertEquals(1, emp1Entry.getOnTimeDays());
        assertEquals(3, emp1Entry.getExpectedWorkingDays());

        PunctualityLeaderboardEntry emp2Entry = result.getLeaderboard().stream()
                .filter(e -> e.getEmployeeUserId().equals(emp2Id)).findFirst().orElseThrow();
        assertEquals(0, emp2Entry.getOnTimeDays());
    }

    @Test
    void ranksDescByPercentage() {
        stubSchedules(Set.of(mon, tue), Set.of(mon, tue));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), mon, wed)).thenReturn(List.of(
                record(emp1Id, mon, "PRESENT"), // emp1: 1/2 = 50%
                record(emp2Id, mon, "PRESENT"), record(emp2Id, tue, "PRESENT") // emp2: 2/2 = 100%
        ));

        TeamPunctualityResponse result = attendanceService.getTeamPunctuality(managerEmail, mon, wed);

        assertEquals(emp2Id, result.getLeaderboard().get(0).getEmployeeUserId());
        assertEquals(100.0, result.getLeaderboard().get(0).getPercentage(), 0.01);
        assertEquals(emp1Id, result.getLeaderboard().get(1).getEmployeeUserId());
        assertEquals(50.0, result.getLeaderboard().get(1).getPercentage(), 0.01);
    }

    @Test
    void tie_leavesBothEntries_withNoInventedSecondaryOrdering() {
        stubSchedules(Set.of(mon), Set.of(mon));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), mon, wed)).thenReturn(List.of(
                record(emp1Id, mon, "PRESENT"), record(emp2Id, mon, "PRESENT")));

        TeamPunctualityResponse result = attendanceService.getTeamPunctuality(managerEmail, mon, wed);

        assertEquals(2, result.getLeaderboard().size());
        assertTrue(result.getLeaderboard().stream().allMatch(e -> e.getPercentage() == 100.0));
    }

    @Test
    void zeroExpectedWorkingDays_excludedFromLeaderboardEntirely() {
        stubSchedules(Set.of(mon, tue), Set.of());
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), mon, wed)).thenReturn(List.of(
                record(emp1Id, mon, "PRESENT")));

        TeamPunctualityResponse result = attendanceService.getTeamPunctuality(managerEmail, mon, wed);

        assertEquals(1, result.getLeaderboard().size());
        assertEquals(emp1Id, result.getLeaderboard().get(0).getEmployeeUserId());
    }

    @Test
    void noDirectReports_returnsEmptyResult() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of());

        TeamPunctualityResponse result = attendanceService.getTeamPunctuality(managerEmail, mon, wed);

        assertTrue(result.getLeaderboard().isEmpty());
        assertTrue(result.getDaily().isEmpty());
        assertEquals(0, result.getSummary().getMaximumEmployeesOnTime());
    }

    @Test
    void dailyChart_includesOnlyDatesThatAreAWorkingDayForAtLeastOneDirectReport() {
        // emp1 works Mon/Tue only; emp2 works Wed only — Mon/Tue/Wed should all appear once each.
        stubSchedules(Set.of(mon, tue), Set.of(wed));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), mon, wed)).thenReturn(List.of(
                record(emp1Id, mon, "PRESENT"), record(emp2Id, wed, "PRESENT")));

        TeamPunctualityResponse result = attendanceService.getTeamPunctuality(managerEmail, mon, wed);

        assertEquals(3, result.getDaily().size());
        Map<LocalDate, Integer> byDate = result.getDaily().stream()
                .collect(java.util.stream.Collectors.toMap(DailyPunctuality::getDate, DailyPunctuality::getEmployeesOnTime));
        assertEquals(1, byDate.get(mon));
        assertEquals(0, byDate.get(tue));
        assertEquals(1, byDate.get(wed));
    }

    @Test
    void summary_averagesMinMax_fromApplicableDailyValuesOnly() {
        stubSchedules(Set.of(mon, tue, wed), Set.of(mon, tue, wed));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(emp1Id, emp2Id), mon, wed)).thenReturn(List.of(
                record(emp1Id, mon, "PRESENT"), record(emp2Id, mon, "PRESENT"), // 2 on time Monday
                record(emp1Id, tue, "PRESENT") // 1 on time Tuesday
                // Wednesday: 0 on time
        ));

        TeamPunctualityResponse result = attendanceService.getTeamPunctuality(managerEmail, mon, wed);

        assertEquals(1.0, result.getSummary().getAverageEmployeesOnTime(), 0.01); // (2+1+0)/3
        assertEquals(0, result.getSummary().getMinimumEmployeesOnTime());
        assertEquals(2, result.getSummary().getMaximumEmployeesOnTime());
    }
}
