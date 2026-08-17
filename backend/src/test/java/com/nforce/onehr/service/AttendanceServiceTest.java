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
import org.junit.jupiter.api.Assumptions;
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
 * Covers two related but distinct guards on a forgotten-open session:
 *  - Still within its own workday/grace window (shiftDayCutover): a late-arriving checkout is
 *    still accepted, but capped at the shift's own natural end — the "27h 8m" bug fix — never at
 *    the actual (possibly much later) click time.
 *  - Past its grace window entirely: no longer accepted or capped at all — flagged Missing
 *    Check-Out instead, with no fabricated checkOutAt or computed workedMinutes.
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
    void checkOut_rejectsAndFlagsMissingCheckout_whenSessionWasLeftOpenForOverADay() {
        // Checked in two days ago at 5:35 PM, last resumed at 6:00 PM, and never checked out.
        // This used to be silently accepted with worked-minutes capped at the shift's natural
        // end (the "27h 8m" bug fix) — but two days is unambiguously past the grace window, so it
        // is no longer accepted or capped at all: no fabricated checkOutAt, no computed
        // workedMinutes, just flagged for correction via regularization.
        LocalDate workDate = currentShiftDay().minusDays(2);
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

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail));

        assertEquals("MISSING_CHECKOUT", open.getStatus());
        assertNull(open.getCheckOutAt());
        assertNull(open.getWorkedMinutes());
    }

    @Test
    void checkOut_stillCapsAtShiftEnd_forALateClickStillWithinTheGraceWindow() {
        // Only meaningful between the shift's natural end (12:30 AM) and the grace-window cutover
        // (7:00 AM, see AttendanceProperties.shiftDayCutover) — the narrow real-time window where
        // a late-but-still-correctable click needs its worked-minutes capped, rather than either
        // using the late click's own time (would inflate hours) or being rejected outright (not
        // yet past grace). Outside that window this scenario doesn't apply, so the test is
        // skipped rather than asserting something time-dependent as if it always holds — see
        // checkOut_rejectsAndFlagsMissingCheckout_... and checkOut_usesActualClickTime_... for the
        // two deterministic (always-applicable) cases on either side of this window.
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDate workDate = currentShiftDay();
        LocalDateTime shiftEnd = LocalDateTime.of(workDate.plusDays(1), LocalTime.of(0, 30));
        Assumptions.assumeTrue(now.isAfter(shiftEnd), "only applicable between shift end (12:30 AM) and grace cutover (7:00 AM)");

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

        int expectedMinutes = (int) Math.round(Duration.between(sessionStart, shiftEnd).getSeconds() / 60.0);
        assertEquals(shiftEnd, response.getCheckOutAt());
        assertEquals(expectedMinutes, response.getWorkedMinutes());
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

    // ---------------------------------------------------------------- missing check-out

    /**
     * The shift-day (per AttendanceProperties.shiftDayCutover, default 7:00 AM) that "right now"
     * belongs to — computed independently of the service so tests can construct a workDate that
     * deterministically IS or ISN'T past its own grace window, regardless of what real wall-clock
     * time the suite happens to run at.
     */
    private static LocalDate currentShiftDay() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        return now.toLocalTime().isBefore(LocalTime.of(7, 0)) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    @Test
    void getToday_flagsMissingCheckout_forStaleOpenSession_andReportsFreshCanCheckIn() {
        // Checked in 6 shift-days ago and never checked out — the exact shape of a forgotten
        // session that would otherwise show as "still checked in" forever (see
        // flagMissingCheckoutIfStale). Always past its own grace window regardless of the current
        // time of day.
        LocalDate workDate = currentShiftDay().minusDays(6);
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

        // Flagged Missing Check-Out — never a fabricated checkOutAt or computed workedMinutes;
        // the real check-out time is unknown, so none is guessed.
        assertEquals("MISSING_CHECKOUT", stale.getStatus());
        assertNull(stale.getCheckOutAt(), "a missing check-out must never be assigned a fabricated check-out time");
        assertNull(stale.getWorkedMinutes(), "worked hours must never be computed from an assumed check-out");
        // ...and today's own state is reported fresh: nothing from 6 shift-days ago blocks a new
        // check-in today.
        assertTrue(response.isCanCheckIn(), "an old, now-flagged session must not block today's check-in");
        assertFalse(response.isCanCheckOut(), "a flagged Missing Check-Out record must not offer Check Out");
        assertNull(response.getRecord());
    }

    @Test
    void getToday_leavesGenuineOvernightSessionOpen_whenStillWithinGraceWindow() {
        // Checked in during the current shift-day (however long ago that shift started) and
        // hasn't yet crossed its own grace window (shiftDayCutover) — the legitimate
        // midnight-crossing case, not a stale session, regardless of what time this test runs at.
        LocalDate workDate = currentShiftDay();
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

        assertEquals("PRESENT", open.getStatus(), "a session still within its own grace window must not be flagged");
        assertNull(open.getCheckOutAt());
        assertFalse(response.isCanCheckIn());
        assertTrue(response.isCanCheckOut());
        assertNotNull(response.getRecord());
    }

    @Test
    void checkIn_flagsMissingCheckoutForStaleOpenSession_thenProceedsWithFreshCheckIn() {
        LocalDate staleWorkDate = currentShiftDay().minusDays(6);
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

        // Must not throw "You have already checked in today" — the stale session is flagged
        // Missing Check-Out first, then a brand-new attendance record is created for today, same
        // as if there had been no prior record at all.
        AttendanceResponse response = service.checkIn(employeeEmail);

        assertEquals("MISSING_CHECKOUT", stale.getStatus());
        assertNull(stale.getCheckOutAt(), "a missing check-out must never be assigned a fabricated check-out time");
        assertNotEquals(stale.getId(), response.getId(), "a fresh check-in must open a new record, not reuse the stale one");
        assertNull(response.getCheckOutAt());
    }

    @Test
    void checkOut_rejectsExplicitClick_onceSessionIsPastItsGraceWindow() {
        // The frontend should never offer Check Out for a record this stale (getToday reports
        // canCheckOut=false for it) — but a request that arrives anyway must not be allowed to
        // fabricate a checkout, and must instead flag the record and reject the click.
        LocalDate staleWorkDate = currentShiftDay().minusDays(6);
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

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail));

        assertEquals("MISSING_CHECKOUT", stale.getStatus());
        assertNull(stale.getCheckOutAt());
        assertNull(stale.getWorkedMinutes());
    }
}
