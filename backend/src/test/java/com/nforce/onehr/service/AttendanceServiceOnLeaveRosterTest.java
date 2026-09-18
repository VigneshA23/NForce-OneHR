package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Approved leave on the HR and Manager day rosters.
 *
 * <p>Before this, an employee on approved leave had no attendance row and so rendered with a blank
 * status — indistinguishable from someone who simply had not shown up. HR had no way to tell an
 * accounted-for absence from an unexplained one without leaving the screen.
 *
 * <p>Pure Mockito, matching {@code AttendanceServiceTeamStatsTest}: no {@code @SpringBootTest}
 * against the citext-incompatible H2 profile.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceServiceOnLeaveRosterTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private AttendancePunchRepository attendancePunchRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private AttendanceProperties props;
    @Mock private WorkingDayService workingDayService;
    @Mock private ExpectedWorkHoursService expectedWorkHoursService;
    @Mock private AttendanceRulesService attendanceRulesService;
    @Mock private AttendanceInterpretationService attendanceInterpretationService;
    @Mock private ShiftDayPolicy shiftDayPolicy;
    @Mock private EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver;
    @Mock private com.nforce.onehr.repository.AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private com.nforce.onehr.repository.WebClockInRequestRepository webClockInRequestRepository;
    @Mock private com.nforce.onehr.repository.AttendanceExceptionRepository attendanceExceptionRepository;
    @Mock private LatePenaltyService latePenaltyService;

    @InjectMocks private AttendanceService attendanceService;

    private final UUID onLeaveId = UUID.randomUUID();
    private final UUID absentId = UUID.randomUUID();
    private final UUID presentId = UUID.randomUUID();

    private final LocalDate day = LocalDate.of(2026, 8, 3);

    private Employee onLeave;
    private Employee absent;
    private Employee present;

    @BeforeEach
    void setUp() {
        onLeave = Employee.builder().userId(onLeaveId).fullName("Ava OnLeave").employeeCode("NF-1").build();
        absent = Employee.builder().userId(absentId).fullName("Ben Absent").employeeCode("NF-2").build();
        present = Employee.builder().userId(presentId).fullName("Cara Present").employeeCode("NF-3").build();

        when(attendanceRulesService.getDefaultZoneId()).thenReturn(java.time.ZoneId.of("Asia/Kolkata"));
        when(attendanceRulesService.resolveEmployeeZoneId(any())).thenReturn(java.time.ZoneId.of("Asia/Kolkata"));
        // toResponse reads the scheduled window for any row that has a real attendance record.
        // EMPTY is the service's own "no shift interpretation" value, which is what an employee
        // with no assigned shift genuinely produces - see AttendanceInterpretationService.
        when(attendanceInterpretationService.resolveScheduledWindow(any()))
                .thenReturn(AttendanceInterpretationService.ScheduledShiftWindow.EMPTY);
    }

    private LeaveRequest approvedLeave(UUID employeeUserId) {
        return LeaveRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeUserId)
                .startDate(day)
                .endDate(day)
                .status("APPROVED")
                .build();
    }

    private void rosterOf(List<Employee> employees, List<Attendance> records, List<LeaveRequest> leave) {
        when(employeeRepository.findAllWithDetails()).thenReturn(employees);
        when(attendanceRepository.findByWorkDate(day)).thenReturn(records);
        when(leaveRequestRepository
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        any(), eq("APPROVED"), eq(day), eq(day)))
                .thenReturn(leave);
    }

    private AttendanceResponse rowFor(List<AttendanceResponse> rows, UUID userId) {
        return rows.stream().filter(r -> userId.equals(r.getEmployeeUserId())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("an employee on approved leave shows ON_LEAVE instead of a blank")
    void approvedLeaveFillsTheBlankStatus() {
        rosterOf(List.of(onLeave), List.of(), List.of(approvedLeave(onLeaveId)));

        List<AttendanceResponse> rows = attendanceService.getDayForAll(day);

        assertThat(rowFor(rows, onLeaveId).getStatus()).isEqualTo("ON_LEAVE");
    }

    @Test
    @DisplayName("an employee with no record and no leave still shows blank, not ON_LEAVE")
    void noLeaveStillMeansNoStatus() {
        rosterOf(List.of(absent), List.of(), List.of());

        List<AttendanceResponse> rows = attendanceService.getDayForAll(day);

        // The blank is correct here. Turning every missing row into a status would replace one
        // wrong answer with another, and ABSENT specifically is a judgement this method has never
        // made - it does not know about holidays, weekends or shift patterns.
        assertThat(rowFor(rows, absentId).getStatus()).isNull();
    }

    @Test
    @DisplayName("approved leave never overrides a real attendance record")
    void attendanceRecordWinsOverLeave() {
        Attendance punched = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(presentId)
                .workDate(day)
                .checkInAt(LocalDateTime.of(day, java.time.LocalTime.of(9, 0)))
                .status("PRESENT")
                .build();

        // Someone with approved leave who came in anyway. The punch is what actually happened, and
        // a roster that hid it behind "On Leave" would be lying about a day they worked.
        rosterOf(List.of(present), List.of(punched), List.of(approvedLeave(presentId)));

        List<AttendanceResponse> rows = attendanceService.getDayForAll(day);

        assertThat(rowFor(rows, presentId).getStatus()).isEqualTo("PRESENT");
    }

    @Test
    @DisplayName("leave is matched to its own employee, not smeared across the roster")
    void leaveAppliesOnlyToTheEmployeeWhoHasIt() {
        rosterOf(List.of(onLeave, absent), List.of(), List.of(approvedLeave(onLeaveId)));

        List<AttendanceResponse> rows = attendanceService.getDayForAll(day);

        assertThat(rowFor(rows, onLeaveId).getStatus()).isEqualTo("ON_LEAVE");
        assertThat(rowFor(rows, absentId).getStatus()).isNull();
    }

    @Test
    @DisplayName("leave is looked up once for the whole roster, not once per employee")
    void leaveLookupIsNotAnNPlusOne() {
        rosterOf(List.of(onLeave, absent, present), List.of(), List.of(approvedLeave(onLeaveId)));

        attendanceService.getDayForAll(day);

        // These rosters render every active employee in the organisation. A per-row lookup would be
        // an N+1 against the largest table this screen touches, and would not show up in testing
        // against a handful of seeded employees.
        verify(leaveRequestRepository, org.mockito.Mockito.times(1))
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        any(Collection.class), anyString(), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    @DisplayName("an empty roster asks the leave table nothing at all")
    void emptyRosterSkipsTheQuery() {
        rosterOf(List.of(), List.of(), List.of());

        assertThat(attendanceService.getDayForAll(day)).isEmpty();
        verify(leaveRequestRepository, never())
                .findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        any(Collection.class), anyString(), any(LocalDate.class), any(LocalDate.class));
    }
}
