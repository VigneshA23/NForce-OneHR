package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendancePunchRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the "27h 8m" bug: a checkout arriving a day (or more) late for a forgotten-open session
 * must have its worked-minutes bounded to the shift's own natural end, not the stale click's real
 * clock time.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private AttendancePunchRepository attendancePunchRepository;
    @Mock private WebClockInRequestRepository webClockInRequestRepository;
    @Mock private AttendanceExceptionRepository attendanceExceptionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private LatePenaltyService latePenaltyService;
    @Mock private WorkingDayService workingDayService;

    private AttendanceService service;

    private final UUID employeeId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";

    @BeforeEach
    void setUp() {
        AttendanceProperties props = new AttendanceProperties();
        service = new AttendanceService(attendanceRepository, attendancePunchRepository, webClockInRequestRepository,
                attendanceExceptionRepository, employeeRepository, managerHistoryRepository,
                auditService, auditSnapshot, props, latePenaltyService, workingDayService);

        Shift shift = Shift.builder().name("Regular").startTime(LocalTime.of(15, 30)).endTime(LocalTime.of(0, 30)).build();
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(shift).build();
        lenient().when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));
        lenient().when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(attendancePunchRepository.findByAttendanceRecordIdAndCheckOutAtIsNull(any()))
                .thenReturn(Optional.empty());
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
    }

    @Test
    void checkOut_capsWorkedMinutesAtShiftEnd_whenSessionWasLeftOpenForOverADay() {
        // Checked in two days ago at 5:35 PM, last resumed at 6:00 PM, and never checked out —
        // exactly the forgotten-session shape that produced "27h 8m" for a checkout that finally
        // arrives today.
        LocalDate workDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(2);
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(17, 35));
        LocalDateTime sessionStart = LocalDateTime.of(workDate, LocalTime.of(18, 0));
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(sessionStart)
                .lateByMinutes(125)
                .status("LATE")
                .build();
        when(attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(open));

        AttendanceResponse response = service.checkOut(employeeEmail);

        // The shift (3:30 PM - 12:30 AM) naturally ends at 00:30 the day after check-in — worked
        // minutes must be bounded to that window, never to "days later, whenever checkout was
        // finally clicked".
        LocalDateTime expectedCap = LocalDateTime.of(workDate.plusDays(1), LocalTime.of(0, 30));
        int expectedMinutes = (int) Math.round(Duration.between(sessionStart, expectedCap).getSeconds() / 60.0);

        assertEquals(expectedCap, response.getCheckOutAt());
        assertEquals(expectedMinutes, response.getWorkedMinutes());
        assertTrue(response.getWorkedMinutes() < 24 * 60, "worked minutes must never span more than a single day");
    }

    @Test
    void checkOut_usesActualClickTime_whenCheckoutHappensWithinTheSameShift() {
        LocalDate workDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(15, 40));
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .build();
        when(attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(open));

        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalDateTime before = LocalDateTime.now(istZone);
        AttendanceResponse response = service.checkOut(employeeEmail);
        LocalDateTime after = LocalDateTime.now(istZone);

        // A normal same-shift checkout is unaffected by the cap: checkOutAt is the real click
        // time, not clamped to the shift-end boundary.
        assertFalse(response.getCheckOutAt().isBefore(before));
        assertFalse(response.getCheckOutAt().isAfter(after));
    }
}
