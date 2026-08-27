package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests, following the same isolation pattern as
 * LeaveServiceTest — no @SpringBootTest/H2 (the H2 test profile can't create
 * schema for citext-typed entities).
 */
@ExtendWith(MockitoExtension.class)
class ExceptionServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private AttendanceExceptionRepository attendanceExceptionRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private RegularizationRequestRepository regularizationRequestRepository;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private AttendancePenaltyEvaluationService attendancePenaltyEvaluationService;
    @Mock private EmailService emailService;
    @Mock private WorkingDayService workingDayService;
    @Mock private HolidayRepository holidayRepository;
    @Mock private PenalizationPolicyResolutionService penalizationPolicyResolutionService;
    @Mock private ExpectedWorkHoursService expectedWorkHoursService;
    @Mock private WorkHoursShortageCalculationService workHoursShortageCalculationService;

    @InjectMocks private ExceptionService exceptionService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final String managerEmail = "manager@test.com";
    private final String hrEmail = "hr@test.com";

    // Zone-matched to AttendanceProperties.getZone() ("Asia/Kolkata", stubbed below) — the service
    // computes "today" against that zone, not the JVM's default zone; using a plain LocalDate.now()
    // here made this test's "today" and the service's "today" disagree (and openToday/missingPunch
    // assertions flip) whenever the machine's local date and Kolkata's calendar date differ, e.g.
    // late evening in an zone west of Kolkata.
    private final LocalDate to = LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
    private final LocalDate from = to.minusDays(6);

    @BeforeEach
    void setUp() {
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(attendanceProperties.getShiftStart()).thenReturn(LocalTime.of(9, 30));
        // Default: employeeId is the only account holding the EMPLOYEE role — matches
        // EmployeeService.listEmployees()'s own definition of who counts as an employee.
        lenient().when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of(employeeId));
        lenient().when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), anyString(), any(), any())).thenReturn(List.of());
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());
    }

    private User userWithRole(String email, String roleCode, UUID id) {
        Role role = Role.builder().code(roleCode).build();
        return User.builder().id(id).email(email).roles(Set.of(role)).build();
    }

    @Test
    void hrAdmin_scopeIsExactlyEmployeeRoleAccounts() {
        UUID otherEmployeeId = UUID.randomUUID();
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));
        when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of(employeeId, otherEmployeeId));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(anyList(), eq(from), eq(to)))
                .thenReturn(List.of());

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceRepository).findByEmployeeUserIdInAndWorkDateBetween(
                argThat(ids -> ids.containsAll(List.of(employeeId, otherEmployeeId)) && ids.size() == 2),
                eq(from), eq(to));
    }

    @Test
    void manager_isScopedToDirectReports() {
        User manager = userWithRole(managerEmail, "MANAGER", managerId);
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(manager));
        when(historyRepository.findByManagerUserIdAndEffectiveToIsNull(managerId))
                .thenReturn(List.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of());

        exceptionService.getExceptionsForCaller(managerEmail, from, to);

        verify(attendanceRepository).findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to);
    }

    @Test
    void manager_excludesDirectReportWhoIsNotAnEmployeeRoleAccount() {
        UUID managerDirectReportId = UUID.randomUUID(); // e.g. a matrixed sub-manager
        User manager = userWithRole(managerEmail, "MANAGER", managerId);
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(manager));
        // employeeRoleIds (from setUp) contains only employeeId, not managerDirectReportId
        when(historyRepository.findByManagerUserIdAndEffectiveToIsNull(managerId)).thenReturn(List.of(
                EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build(),
                EmployeeManagerHistory.builder().employeeUserId(managerDirectReportId).managerUserId(managerId).build()));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of());

        exceptionService.getExceptionsForCaller(managerEmail, from, to);

        verify(attendanceRepository).findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to);
        verify(attendanceRepository, never()).findByEmployeeUserIdInAndWorkDateBetween(
                argThat(ids -> ids.contains(managerDirectReportId)), any(), any());
    }

    @Test
    void employeeWithNoRole_isDenied() {
        User plain = userWithRole(employeeEmail, "EMPLOYEE", employeeId);
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(plain));

        assertThrows(AccessDeniedException.class,
                () -> exceptionService.getExceptionsForCaller(employeeEmail, from, to));
    }

    @Test
    void lateArrival_isDetectedFromRealAttendanceData() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        Attendance lateRecord = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(LocalDate.now().minusDays(1))
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(10).withMinute(0))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .lateByMinutes(15)
                .build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(lateRecord));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, lateRecord.getWorkDate(), ExceptionType.LATE_ARRIVAL)).thenReturn(Optional.empty());
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository).save(argThat(exc ->
                exc.getExceptionType().equals(ExceptionType.LATE_ARRIVAL)
                        && exc.getMinutesLate().equals(15)));
    }

    @Test
    void missingPunch_isDetectedForPastDayWithNoCheckout_butNotForToday() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        Attendance missingPunchYesterday = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(to.minusDays(1))
                .checkInAt(to.minusDays(1).atTime(9, 30))
                .checkOutAt(null)
                .lateByMinutes(0)
                .build();
        Attendance openToday = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(to)
                .checkInAt(to.atTime(9, 30))
                .checkOutAt(null)
                .lateByMinutes(0)
                .build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(missingPunchYesterday, openToday));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, missingPunchYesterday.getWorkDate(), ExceptionType.MISSING_PUNCH)).thenReturn(Optional.empty());
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, times(1)).save(any(AttendanceException.class));
        verify(attendanceExceptionRepository).save(argThat(exc ->
                exc.getExceptionType().equals(ExceptionType.MISSING_PUNCH)
                        && exc.getExceptionDate().equals(missingPunchYesterday.getWorkDate())));
    }

    @Test
    void detection_upsertsExistingExceptionInstead_ofDuplicating() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        Attendance lateRecord = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(LocalDate.now().minusDays(1))
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(10).withMinute(0))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .lateByMinutes(30)
                .build();
        AttendanceException existing = AttendanceException.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .exceptionDate(lateRecord.getWorkDate())
                .exceptionType(ExceptionType.LATE_ARRIVAL)
                .minutesLate(10)
                .build();
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(lateRecord));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, lateRecord.getWorkDate(), ExceptionType.LATE_ARRIVAL)).thenReturn(Optional.of(existing));
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, times(1)).save(argThat(exc ->
                exc.getId().equals(existing.getId()) && exc.getMinutesLate().equals(30)));
    }

    @Test
    void leaveAttendanceConflict_isDetectedWhenApprovedLeaveOverlapsAnAttendanceRecord() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        LocalDate conflictDate = LocalDate.now().minusDays(1);
        Attendance recordOnLeaveDay = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(conflictDate)
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(9).withMinute(30))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .lateByMinutes(0)
                .build();
        LeaveRequest approvedLeave = LeaveRequest.builder()
                .employeeUserId(employeeId)
                .startDate(conflictDate)
                .endDate(conflictDate)
                .status("APPROVED")
                .build();

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(recordOnLeaveDay));
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                List.of(employeeId), "APPROVED", to, from)).thenReturn(List.of(approvedLeave));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, conflictDate, ExceptionType.LEAVE_ATTENDANCE_CONFLICT)).thenReturn(Optional.empty());
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository).save(argThat(exc ->
                exc.getExceptionType().equals(ExceptionType.LEAVE_ATTENDANCE_CONFLICT)
                        && exc.getExceptionDate().equals(conflictDate)));
    }

    @Test
    void leaveAttendanceConflict_notRaised_whenLeaveDoesNotCoverTheCheckInDay() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        LocalDate checkInDate = LocalDate.now().minusDays(1);
        LocalDate leaveDate = LocalDate.now().minusDays(3); // does not overlap checkInDate
        Attendance recordOutsideLeave = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(checkInDate)
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(9).withMinute(30))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .lateByMinutes(0)
                .build();
        LeaveRequest approvedLeave = LeaveRequest.builder()
                .employeeUserId(employeeId)
                .startDate(leaveDate)
                .endDate(leaveDate)
                .status("APPROVED")
                .build();

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(recordOutsideLeave));
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                List.of(employeeId), "APPROVED", to, from)).thenReturn(List.of(approvedLeave));

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, never()).save(argThat(exc ->
                exc.getExceptionType().equals(ExceptionType.LEAVE_ATTENDANCE_CONFLICT)));
    }

    @Test
    void leaveAttendanceConflict_reDetection_emailsOnlyOnce() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));

        LocalDate conflictDate = LocalDate.now().minusDays(1);
        Attendance recordOnLeaveDay = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(conflictDate)
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(9).withMinute(30))
                .checkOutAt(LocalDateTime.now().minusDays(1).withHour(18).withMinute(0))
                .lateByMinutes(0)
                .build();
        LeaveRequest approvedLeave = LeaveRequest.builder()
                .employeeUserId(employeeId)
                .startDate(conflictDate)
                .endDate(conflictDate)
                .status("APPROVED")
                .build();
        AttendanceException existing = AttendanceException.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .exceptionDate(conflictDate)
                .exceptionType(ExceptionType.LEAVE_ATTENDANCE_CONFLICT)
                .build();

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of(recordOnLeaveDay));
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                List.of(employeeId), "APPROVED", to, from)).thenReturn(List.of(approvedLeave));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, conflictDate, ExceptionType.LEAVE_ATTENDANCE_CONFLICT)).thenReturn(Optional.of(existing));
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        exceptionService.getExceptionsForCaller(hrEmail, from, to);
        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, times(2)).save(argThat(exc ->
                exc.getExceptionType().equals(ExceptionType.LEAVE_ATTENDANCE_CONFLICT)));
    }
}
