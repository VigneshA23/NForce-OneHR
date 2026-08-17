package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
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
import java.util.List;
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
        lenient().when(attendancePunchRepository.findFirstByAttendanceRecordIdAndCheckOutAtIsNullOrderByCheckInAtDesc(any()))
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

    // ---------------------------------------------------------------- stale open session

    @Test
    void getToday_autoClosesStaleOpenSession_andReportsFreshCanCheckIn() {
        // Checked in 6 days ago and never checked out — the exact shape of a forgotten session
        // that would otherwise show as "still checked in" forever (see autoCloseIfStale).
        LocalDate workDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(6);
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(18, 4));
        Attendance stale = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .build();
        when(attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(stale));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.empty());

        TodayAttendanceResponse response = service.getToday(employeeEmail);

        // The stale record is closed (capped at its own shift's end, exactly like an explicit
        // late checkout — see checkOut_capsWorkedMinutesAtShiftEnd_...), not left open...
        assertNotNull(stale.getCheckOutAt(), "stale session must be auto-closed, not left open");
        LocalDateTime expectedCap = LocalDateTime.of(workDate.plusDays(1), LocalTime.of(0, 30));
        assertEquals(expectedCap, stale.getCheckOutAt());
        // ...and today's own state is reported fresh: nothing from 6 days ago blocks a new
        // check-in today.
        assertTrue(response.isCanCheckIn(), "an old, now-closed session must not block today's check-in");
        assertFalse(response.isCanCheckOut());
        assertNull(response.getRecord());
    }

    @Test
    void getToday_leavesGenuineOvernightSessionOpen_whenStillWithinShiftWindow() {
        // Checked in today; shift (3:30 PM - 12:30 AM) doesn't end until tomorrow 00:30 — always
        // still ahead of "now" regardless of what time this test happens to run, so this is
        // deterministically the legitimate midnight-crossing case, not a stale session.
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

        TodayAttendanceResponse response = service.getToday(employeeEmail);

        assertNull(open.getCheckOutAt(), "a session still within its own shift window must not be auto-closed");
        assertFalse(response.isCanCheckIn());
        assertTrue(response.isCanCheckOut());
        assertNotNull(response.getRecord());
    }

    @Test
    void checkIn_autoClosesStaleOpenSession_thenProceedsWithFreshCheckIn() {
        LocalDate staleWorkDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(6);
        LocalDateTime staleCheckInAt = LocalDateTime.of(staleWorkDate, LocalTime.of(18, 4));
        Attendance stale = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(staleWorkDate)
                .checkInAt(staleCheckInAt)
                .sessionStartedAt(staleCheckInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .build();
        when(attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(stale));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.empty());
        when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(any()))
                .thenReturn(List.of());

        // Must not throw "You have already checked in today" — the stale session is auto-closed
        // first, then a brand-new attendance record is created for today, same as if there had
        // been no prior record at all.
        AttendanceResponse response = service.checkIn(employeeEmail);

        assertNotNull(stale.getCheckOutAt(), "stale session must be auto-closed before a fresh check-in proceeds");
        assertNotEquals(stale.getId(), response.getId(), "a fresh check-in must open a new record, not reuse the stale one");
        assertNull(response.getCheckOutAt());
    }
}
