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
    @Mock private AttendanceProperties attendanceProperties;

    @InjectMocks private ExceptionService exceptionService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final String managerEmail = "manager@test.com";
    private final String hrEmail = "hr@test.com";

    private final LocalDate from = LocalDate.now().minusDays(6);
    private final LocalDate to = LocalDate.now();

    @BeforeEach
    void setUp() {
        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(attendanceProperties.getShiftStart()).thenReturn(LocalTime.of(9, 30));
    }

    private User userWithRole(String email, String roleCode, UUID id) {
        Role role = Role.builder().code(roleCode).build();
        return User.builder().id(id).email(email).roles(Set.of(role)).build();
    }

    @Test
    void hrAdmin_seesCompanyWideExceptions_unscoped() {
        User hr = userWithRole(hrEmail, "HR_ADMIN", UUID.randomUUID());
        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hr));
        when(attendanceRepository.findByWorkDateBetween(from, to)).thenReturn(List.of());
        when(attendanceExceptionRepository.findByExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(from, to))
                .thenReturn(List.of());

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceRepository).findByWorkDateBetween(from, to);
        verify(attendanceRepository, never()).findByEmployeeUserIdInAndWorkDateBetween(any(), any(), any());
    }

    @Test
    void manager_isScopedToDirectReports() {
        User manager = userWithRole(managerEmail, "MANAGER", managerId);
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(manager));
        when(historyRepository.findByManagerUserIdAndEffectiveToIsNull(managerId))
                .thenReturn(List.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to))
                .thenReturn(List.of());
        when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                List.of(employeeId), from, to)).thenReturn(List.of());

        exceptionService.getExceptionsForCaller(managerEmail, from, to);

        verify(attendanceRepository).findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), from, to);
        verify(attendanceRepository, never()).findByWorkDateBetween(any(), any());
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
        when(attendanceRepository.findByWorkDateBetween(from, to)).thenReturn(List.of(lateRecord));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, lateRecord.getWorkDate(), ExceptionType.LATE_ARRIVAL)).thenReturn(Optional.empty());
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(attendanceExceptionRepository.findByExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(from, to))
                .thenReturn(List.of());

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
                .workDate(LocalDate.now().minusDays(1))
                .checkInAt(LocalDateTime.now().minusDays(1).withHour(9).withMinute(30))
                .checkOutAt(null)
                .lateByMinutes(0)
                .build();
        Attendance openToday = Attendance.builder()
                .employeeUserId(employeeId)
                .workDate(LocalDate.now())
                .checkInAt(LocalDateTime.now().withHour(9).withMinute(30))
                .checkOutAt(null)
                .lateByMinutes(0)
                .build();
        when(attendanceRepository.findByWorkDateBetween(from, to))
                .thenReturn(List.of(missingPunchYesterday, openToday));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, missingPunchYesterday.getWorkDate(), ExceptionType.MISSING_PUNCH)).thenReturn(Optional.empty());
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(attendanceExceptionRepository.findByExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(from, to))
                .thenReturn(List.of());

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
        when(attendanceRepository.findByWorkDateBetween(from, to)).thenReturn(List.of(lateRecord));
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, lateRecord.getWorkDate(), ExceptionType.LATE_ARRIVAL)).thenReturn(Optional.of(existing));
        when(attendanceExceptionRepository.save(any(AttendanceException.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(attendanceExceptionRepository.findByExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(from, to))
                .thenReturn(List.of());

        exceptionService.getExceptionsForCaller(hrEmail, from, to);

        verify(attendanceExceptionRepository, times(1)).save(argThat(exc ->
                exc.getId().equals(existing.getId()) && exc.getMinutesLate().equals(30)));
    }
}
