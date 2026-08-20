package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendancePunch;
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
import java.time.ZoneOffset;
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
        lenient().when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(any()))
                .thenReturn(List.of());
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
    }

    /**
     * Stubs {@code record} as the employee's currently-open NORMAL session — checkIn/checkOut/
     * getToday now find this via an open AttendancePunch (see AttendanceService
     * .findOpenNormalAttendance), not a direct query on the Attendance row itself, since Web
     * Clock-In sessions are tracked entirely independently and must never be mistaken for one.
     */
    private void stubOpenNormalSession(Attendance record) {
        LocalDateTime punchCheckIn = record.getSessionStartedAt() != null ? record.getSessionStartedAt() : record.getCheckInAt();
        AttendancePunch openPunch = AttendancePunch.builder()
                .id(UUID.randomUUID())
                .attendanceRecordId(record.getId())
                .checkInAt(punchCheckIn)
                .build();
        lenient().when(attendancePunchRepository.findOpenByEmployeeUserId(employeeId)).thenReturn(List.of(openPunch));
        lenient().when(attendanceRepository.findById(record.getId())).thenReturn(Optional.of(record));
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
        stubOpenNormalSession(open);

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail, null));

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
        stubOpenNormalSession(open);

        AttendanceResponse response = service.checkOut(employeeEmail, null);

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
        stubOpenNormalSession(open);

        ZoneId istZone = ZoneId.of("Asia/Kolkata");
        LocalDateTime before = LocalDateTime.now(istZone);
        AttendanceResponse response = service.checkOut(employeeEmail, null);
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
        stubOpenNormalSession(stale);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.empty());

        TodayAttendanceResponse response = service.getToday(employeeEmail, null);

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
        stubOpenNormalSession(open);

        TodayAttendanceResponse response = service.getToday(employeeEmail, null);

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
        stubOpenNormalSession(stale);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.empty());

        // Must not throw "You have already checked in today" — the stale session is flagged
        // Missing Check-Out first, then a brand-new attendance record is created for today, same
        // as if there had been no prior record at all.
        AttendanceResponse response = service.checkIn(employeeEmail, null);

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
        stubOpenNormalSession(stale);

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail, null));

        assertEquals("MISSING_CHECKOUT", stale.getStatus());
        assertNull(stale.getCheckOutAt());
        assertNull(stale.getWorkedMinutes());
    }

    // ---------------------------------------------------------------- browser timezone

    @Test
    void checkIn_usesBrowserReportedTimezone_notTheEmployeesConfiguredLocation() {
        // No Location on this employee (see setUp) — without a browser-reported zone this would
        // fall back to the global default (Asia/Kolkata). Australia/Adelaide (a genuine IANA
        // zone, UTC+9:30/+10:30) exercises a half-hour offset distinct from IST's own +5:30.
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any()))
                .thenReturn(Optional.empty());

        AttendanceResponse response = service.checkIn(employeeEmail, "Australia/Adelaide");

        assertEquals("Australia/Adelaide", response.getTimezone(),
                "the resolved zone must be locked onto the record, not silently dropped");
        LocalDateTime expectedNow = LocalDateTime.now(ZoneId.of("Australia/Adelaide"));
        assertEquals(expectedNow.toLocalDate(), response.getCheckInAt().toLocalDate());
        assertTrue(Duration.between(response.getCheckInAt(), expectedNow).abs().toSeconds() < 5,
                "checkInAt must reflect the browser's reported zone's wall clock, not IST");
    }

    @Test
    void checkIn_ignoresInvalidBrowserTimezone_fallsBackToConfiguredZone() {
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any()))
                .thenReturn(Optional.empty());

        // Not a real IANA zone — must not throw, must fall back silently (this employee has no
        // Location, so the fallback is the global default zone, "Asia/Kolkata").
        AttendanceResponse response = service.checkIn(employeeEmail, "not-a-real-timezone");

        assertEquals("Asia/Kolkata", response.getTimezone());
    }

    @Test
    void checkOut_usesTheSessionsLockedInZone_ignoringADifferentBrowserZoneAtCheckoutTime() {
        // Checked in from a UTC+10:30 browser (Lord Howe Island standard time) earlier today
        // (that shift-day, per the locked zone) — session still open.
        LocalDate workDate = LocalDate.now(ZoneId.of("Australia/Lord_Howe"));
        LocalDateTime checkInAt = LocalDateTime.now(ZoneId.of("Australia/Lord_Howe")).minusHours(1);
        Attendance open = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .lateByMinutes(0)
                .status("PRESENT")
                .timezone("Australia/Lord_Howe")
                .build();
        stubOpenNormalSession(open);

        // Check-out click arrives from a browser now reporting a completely different zone
        // (e.g. a VPN, or genuine travel) — must NOT be used; only the session's own locked zone
        // ("Australia/Lord_Howe") may compute this checkout, so worked-minutes stays correct.
        LocalDateTime beforeLordHowe = LocalDateTime.now(ZoneId.of("Australia/Lord_Howe"));
        AttendanceResponse response = service.checkOut(employeeEmail, "America/New_York");
        LocalDateTime afterLordHowe = LocalDateTime.now(ZoneId.of("Australia/Lord_Howe"));

        assertFalse(response.getCheckOutAt().isBefore(beforeLordHowe));
        assertFalse(response.getCheckOutAt().isAfter(afterLordHowe));
        assertTrue(response.getWorkedMinutes() < 120, "roughly the 1-hour session, not skewed by the mismatched browser zone");
    }

    /**
     * A pure LocalTime-of-day comparison (the old bug) silently breaks lateness for any check-in
     * that has crossed midnight relative to an overnight shift: 1:00 AM as a bare LocalTime reads
     * as "before" a 20:30 shift start, so it would wrongly compute 0 minutes late / PRESENT for a
     * check-in that's actually ~4.5 hours late. Uses a browser-timezone OFFSET chosen so "now" is
     * always exactly 1:00 AM local, regardless of when this test actually runs — avoids a flaky
     * dependency on real wall-clock time while still exercising the exact scenario.
     */
    @Test
    void checkIn_computesLatenessCorrectly_forACheckInThatHasCrossedMidnightOnAnOvernightShift() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(1, 0).toSecondOfDay();
        int nowSecondOfDay = utcNow.toLocalTime().toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - nowSecondOfDay;
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Shift overnightShift = Shift.builder().name("US Night Shift").startTime(LocalTime.of(20, 30)).endTime(LocalTime.of(5, 30)).build();
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.empty());

        AttendanceResponse resp = service.checkIn(employeeEmail, offset.getId());

        assertEquals("LATE", resp.getStatus());
        assertTrue(resp.getLateByMinutes() > 200,
                "expected several hours late (shift started 20:30 the previous day), was " + resp.getLateByMinutes());
    }
}
