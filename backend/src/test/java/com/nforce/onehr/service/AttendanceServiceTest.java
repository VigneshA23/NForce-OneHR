package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.TodayAttendanceResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.AttendancePunch;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Location;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.WebClockInRequest;
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
    void checkOut_recordsTheActualClickTime_butStillCapsWorkedMinutesAtShiftEnd_forALateClickWithinTheGraceWindow() {
        // Only meaningful between the shift's natural end (12:30 AM) and the grace-window cutover
        // (7:00 AM, see AttendanceProperties.shiftDayCutover) — the narrow real-time window where
        // a late-but-still-correctable click's WORKED-MINUTES figure needs capping (so it doesn't
        // inflate into something like "27h 8m"), without that cap ever touching the stored
        // checkOutAt itself — the actual click time is always what gets recorded, everywhere
        // (this was a real reported bug: checkout showing as exactly the shift's end time for
        // every late-but-legitimate click). Outside this window the scenario doesn't apply, so
        // the test is skipped rather than asserting something time-dependent as if it always
        // holds — see checkOut_rejectsAndFlagsMissingCheckout_... and
        // checkOut_usesActualClickTime_... for the two deterministic (always-applicable) cases on
        // either side of this window.
        LocalDateTime beforeNow = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalDate workDate = currentShiftDay();
        LocalDateTime shiftEnd = LocalDateTime.of(workDate.plusDays(1), LocalTime.of(0, 30));
        Assumptions.assumeTrue(beforeNow.isAfter(shiftEnd), "only applicable between shift end (12:30 AM) and grace cutover (7:00 AM)");

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
        // The SAME punch instance backs both lookups closeSession/recomputeCombinedWorkedMinutes
        // make: findFirst...OrderByCheckInAtDesc (closeSession closes it) and
        // findByAttendanceRecordIdOrderByCheckInAtAsc (collectPunches sums it) — closeSession's
        // own punch.setCheckOutAt(...) mutation must be visible to the second lookup, or the
        // worked-minutes sum sees no closed punches at all and silently returns 0.
        AttendancePunch openPunch = AttendancePunch.builder()
                .id(UUID.randomUUID())
                .attendanceRecordId(open.getId())
                .checkInAt(sessionStart)
                .build();
        lenient().when(attendancePunchRepository.findFirstByAttendanceRecordIdAndCheckOutAtIsNullOrderByCheckInAtDesc(open.getId()))
                .thenReturn(Optional.of(openPunch));
        lenient().when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(open.getId()))
                .thenReturn(List.of(openPunch));

        AttendanceResponse response = service.checkOut(employeeEmail, null);
        LocalDateTime afterNow = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));

        int expectedMinutes = (int) Math.round(Duration.between(sessionStart, shiftEnd).getSeconds() / 60.0);
        assertFalse(response.getCheckOutAt().isBefore(beforeNow), "checkOutAt must be the real click time, not before the click");
        assertFalse(response.getCheckOutAt().isAfter(afterNow), "checkOutAt must be the real click time, not after the click");
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
    void checkIn_ignoresBrowserReportedTimezone_alwaysUsesTheEmployeesConfiguredZone() {
        // No Location on this employee (see setUp), so the employee's own zone is the global
        // default (Asia/Kolkata). A browser reporting a completely different zone
        // ("Australia/Adelaide", a genuine IANA zone, UTC+9:30/+10:30 — a half-hour offset
        // distinct from IST's own +5:30) must be ignored entirely: per explicit requirement, the
        // employee's own assigned Location timezone (or the global default, absent one) is the
        // ONLY source for their attendance clock — never the viewer's/browser's own zone.
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any()))
                .thenReturn(Optional.empty());

        AttendanceResponse response = service.checkIn(employeeEmail, "Australia/Adelaide");

        assertEquals("Asia/Kolkata", response.getTimezone(),
                "the browser-reported zone must never override the employee's own configured zone");
        LocalDateTime expectedNow = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        assertEquals(expectedNow.toLocalDate(), response.getCheckInAt().toLocalDate());
        assertTrue(Duration.between(response.getCheckInAt(), expectedNow).abs().toSeconds() < 5,
                "checkInAt must reflect the employee's own configured zone's wall clock, never the browser's");
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

        // The deterministic "now reads as 1:00 AM" trick now has to come from the employee's own
        // configured Location.timezone, not the browser-reported clientTimezone — resolveZone no
        // longer consults the latter at all (Location is the ONLY source, per explicit
        // requirement). ZoneId.of accepts a bare numeric offset ("+05:30" etc.) just as well as a
        // real IANA region name, so this is otherwise the exact same technique as before.
        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift overnightShift = Shift.builder().name("US Night Shift").startTime(LocalTime.of(20, 30)).endTime(LocalTime.of(5, 30)).build();
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.empty());

        AttendanceResponse resp = service.checkIn(employeeEmail, null);

        assertEquals("LATE", resp.getStatus());
        assertTrue(resp.getLateByMinutes() > 200,
                "expected several hours late (shift started 20:30 the previous day), was " + resp.getLateByMinutes());
    }

    // ---------------------------------------------------------------- half day / full day timing

    /**
     * A checkout well before the shift's own natural end is a resumable break (checkIn's own
     * "resume" branch explicitly allows checking in again later the same shift/day) — HALF_DAY
     * must not be judged this early just because worked-minutes-so-far happens to be low. Uses
     * the same deterministic offset trick as the overnight-lateness test above: "now" reads as
     * 8:36 PM, six minutes into a 20:30-05:30 shift — nowhere near its 5:30 AM end.
     */
    @Test
    void checkOut_beforeShiftEnd_doesNotFinalizeHalfDay_evenWithLowWorkedMinutes() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(20, 36).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift overnightShift = Shift.builder().name("US Night Shift").startTime(LocalTime.of(20, 30)).endTime(LocalTime.of(5, 30)).build();
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));

        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId()));
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(20, 30));
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

        AttendanceResponse resp = service.checkOut(employeeEmail, null);

        assertNotEquals("HALF_DAY", resp.getStatus(),
                "shift hasn't ended yet (20:36, shift ends 05:30) — must not judge the day as HALF_DAY this early");
        assertEquals("PRESENT", resp.getStatus(), "status should stay whatever check-in set until the shift actually ends");
    }

    /**
     * Once the shift has actually reached its own natural end, a genuinely short day (checked in
     * right at the very end, checked out minutes later — never came back) must finalize as
     * HALF_DAY: there's no more opportunity to resume. "now" reads as 5:35 AM, five minutes past
     * the 20:30-05:30 shift's own end.
     */
    @Test
    void checkOut_atOrAfterShiftEnd_finalizesHalfDay_whenWorkedMinutesBelowThreshold() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(5, 35).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift overnightShift = Shift.builder().name("US Night Shift").startTime(LocalTime.of(20, 30)).endTime(LocalTime.of(5, 30)).build();
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).build();
        when(employeeRepository.findByUser_Email(employeeEmail)).thenReturn(Optional.of(employee));

        // "Now" (5:35 AM) is past midnight relative to the shift's own start (20:30 the evening
        // before) — the open session's workDate is that earlier calendar day, same convention as
        // the overnight-lateness test above.
        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId())).minusDays(1);
        LocalDateTime checkInAt = LocalDateTime.of(workDate.plusDays(1), LocalTime.of(5, 30));
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

        AttendanceResponse resp = service.checkOut(employeeEmail, null);

        assertEquals("HALF_DAY", resp.getStatus(),
                "shift has ended (05:35, shift ends 05:30) and only ~5 minutes were worked — must finalize as HALF_DAY now");
    }

    /**
     * The sweep covering the case checkOut itself can't: an employee who checked out well
     * before their shift ended and simply never came back that day. Once the shift's own
     * natural end has since passed (checked here, not at the original checkout), the sweep must
     * finalize the day as HALF_DAY on its own, without any further attendance action from the
     * employee.
     */
    @Test
    void finalizeStatusPastShiftEnd_finalizesHalfDay_forAClosedDayThatNeverReopenedAfterShiftEnd() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(5, 35).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift overnightShift = Shift.builder().name("US Night Shift").startTime(LocalTime.of(20, 30)).endTime(LocalTime.of(5, 30)).build();
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId())).minusDays(1);
        Attendance closed = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(LocalDateTime.of(workDate, LocalTime.of(20, 35)))
                .checkOutAt(LocalDateTime.of(workDate, LocalTime.of(20, 40)))
                .workedMinutes(5)
                .lateByMinutes(0)
                .status("PRESENT")
                .build();
        when(attendanceRepository.findByStatusInAndWorkDateGreaterThanEqual(any(), any()))
                .thenReturn(List.of(closed));

        service.finalizeStatusPastShiftEnd();

        assertEquals("HALF_DAY", closed.getStatus());
        verify(attendanceRepository).save(closed);
    }

    /** The mirror case: shift hasn't ended yet, so the sweep must leave the record untouched. */
    @Test
    void finalizeStatusPastShiftEnd_leavesRecordUntouched_whenShiftHasNotEndedYet() {
        LocalDateTime utcNow = LocalDateTime.now(ZoneOffset.UTC);
        int targetSecondOfDay = LocalTime.of(20, 36).toSecondOfDay();
        int offsetSeconds = targetSecondOfDay - utcNow.toLocalTime().toSecondOfDay();
        if (offsetSeconds > 18 * 3600) offsetSeconds -= 24 * 3600;
        if (offsetSeconds < -18 * 3600) offsetSeconds += 24 * 3600;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(offsetSeconds);

        Location location = Location.builder().name("Test Location").timezone(offset.getId()).build();
        Shift overnightShift = Shift.builder().name("US Night Shift").startTime(LocalTime.of(20, 30)).endTime(LocalTime.of(5, 30)).build();
        Employee employee = Employee.builder().userId(employeeId).employeeCode("E1").fullName("Test Employee").shift(overnightShift).location(location).build();
        when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        LocalDate workDate = LocalDate.now(ZoneId.of(offset.getId()));
        Attendance closed = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(LocalDateTime.of(workDate, LocalTime.of(20, 30)))
                .checkOutAt(LocalDateTime.of(workDate, LocalTime.of(20, 31)))
                .workedMinutes(1)
                .lateByMinutes(0)
                .status("PRESENT")
                .build();
        when(attendanceRepository.findByStatusInAndWorkDateGreaterThanEqual(any(), any()))
                .thenReturn(List.of(closed));

        service.finalizeStatusPastShiftEnd();

        assertEquals("PRESENT", closed.getStatus(), "shift ends at 05:30 the next day — 20:36 is nowhere near over yet");
        verify(attendanceRepository, never()).save(any(Attendance.class));
    }

    // ---------------------------------------------------------------- break minutes

    /**
     * A Web Clock-In/Out session can genuinely overlap a normal Check-In/Out session in real
     * time (the two are independent — see WebClockInService's own class Javadoc): here the
     * WEB_REMOTE cycle starts and ends entirely inside the still-open... no, entirely inside the
     * already-closed SYSTEM session's window. collectPunches sorts by checkInAt only, so the
     * "gap" between the SYSTEM session's checkOutAt and the (earlier-ending) WEB_REMOTE session's
     * checkInAt is negative. Must be floored at 0, not surfaced to the employee as e.g.
     * "-6 / 60 min" on the Today's Timings panel.
     */
    @Test
    void getToday_neverReportsANegativeBreakUsedMinutes_whenWebAndNormalSessionsOverlap() {
        LocalDate workDate = currentShiftDay();
        LocalDateTime systemCheckIn = LocalDateTime.of(workDate, LocalTime.of(22, 3));
        LocalDateTime systemCheckOut = LocalDateTime.of(workDate, LocalTime.of(22, 32));
        Attendance closed = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(systemCheckIn)
                .checkOutAt(systemCheckOut)
                .workedMinutes(29)
                .lateByMinutes(0)
                .status("PRESENT")
                .build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), any()))
                .thenReturn(Optional.of(closed));

        AttendancePunch systemPunch = AttendancePunch.builder()
                .id(UUID.randomUUID()).attendanceRecordId(closed.getId())
                .checkInAt(systemCheckIn).checkOutAt(systemCheckOut).build();
        when(attendancePunchRepository.findByAttendanceRecordIdOrderByCheckInAtAsc(closed.getId()))
                .thenReturn(List.of(systemPunch));

        // Web Clock-In/Out cycle entirely inside the SYSTEM session's window — checkInAt 22:07,
        // checkedOutAt 22:08 — both well before the SYSTEM session's own 22:32 checkout.
        WebClockInRequest webCycle = WebClockInRequest.builder()
                .id(UUID.randomUUID()).employeeUserId(employeeId).workDate(workDate)
                .requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(22, 7)))
                .checkedOutAt(LocalDateTime.of(workDate, LocalTime.of(22, 8)))
                .reason("test").status("PENDING").build();
        when(webClockInRequestRepository.findByEmployeeUserIdAndWorkDateOrderByRequestedCheckInAsc(employeeId, workDate))
                .thenReturn(List.of(webCycle));

        TodayAttendanceResponse response = service.getToday(employeeEmail, null);

        assertNotNull(response.getBreakUsedMinutes());
        assertTrue(response.getBreakUsedMinutes() >= 0,
                "break-used minutes must never be negative, was " + response.getBreakUsedMinutes());
        assertEquals(0, response.getBreakUsedMinutes());
    }
}
