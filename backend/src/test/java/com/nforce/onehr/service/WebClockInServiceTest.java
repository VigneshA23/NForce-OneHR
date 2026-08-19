package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.CreateWebClockInRequest;
import com.nforce.onehr.dto.attendance.WebClockInResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.entity.WebClockInRequest;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.repository.WebClockInRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ONEHR-140: even though {@code submit()} now self-approves every new web clock-in (see this
 * service's class javadoc — approve/reject only remain reachable for pre-existing legacy PENDING
 * rows), any PENDING row that IS reviewed must still notify the original requester exactly once.
 */
@ExtendWith(MockitoExtension.class)
class WebClockInServiceTest {

    @Mock private WebClockInRequestRepository webClockInRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private AttendanceProperties attendanceProps;
    @Mock private LatePenaltyService latePenaltyService;
    @Mock private NotificationService notificationService;

    @InjectMocks private WebClockInService service;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID hrAdminId = UUID.randomUUID();
    private final String hrAdminEmail = "hr@test.com";

    @BeforeEach
    void setUp() {
        Role hrRole = Role.builder().id(1).code("HR_ADMIN").displayName("HR Admin").build();
        User hrUser = User.builder().id(hrAdminId).email(hrAdminEmail).roles(Set.of(hrRole)).build();
        lenient().when(userRepository.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrUser));
        lenient().when(webClockInRepository.save(any(WebClockInRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(employeeRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(userRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        // recomputeDerivedFields() (invoked via approve() -> applyCheckInToAttendance()) needs a
        // real shift-start deadline to compute lateByMinutes against.
        lenient().when(attendanceProps.getShiftStart()).thenReturn(LocalTime.of(9, 0));
        lenient().when(attendanceProps.getLateGraceMinutes()).thenReturn(10);
        // approve() -> applyCheckInToAttendance() -> resolveZone() falls back to this when the
        // (mocked, empty) employeeRepository lookup finds no Location.timezone to prefer.
        lenient().when(attendanceProps.getZone()).thenReturn("Asia/Kolkata");
        // shiftDayOf() (called unconditionally by submit()/checkOut()) needs this even when a
        // test isn't specifically exercising the overnight-shift-crossing-midnight behavior.
        lenient().when(attendanceProps.getShiftDayCutover()).thenReturn(LocalTime.of(7, 0));
        lenient().when(attendanceRepository.findByEmployeeUserIdAndWorkDate(any(), any())).thenReturn(Optional.empty());
        lenient().when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));
        // submit() -> resolveAssignedApprover() needs a non-null Optional regardless of whether
        // the test cares about manager assignment.
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(any())).thenReturn(Optional.empty());
    }

    private User employeeUser(String email) {
        Role empRole = Role.builder().id(2).code("EMPLOYEE").displayName("Employee").build();
        return User.builder().id(employeeId).email(email).roles(Set.of(empRole)).build();
    }

    private WebClockInRequest pendingRequest() {
        return WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .assignedApproverId(hrAdminId)
                .workDate(LocalDate.of(2026, 8, 10))
                .requestedCheckIn(LocalDateTime.of(2026, 8, 10, 9, 5))
                .reason("Legacy pending row")
                .status("PENDING")
                .build();
    }

    @Test
    void approve_notifiesOriginalRequester() {
        WebClockInRequest req = pendingRequest();
        when(webClockInRepository.findById(req.getId())).thenReturn(Optional.of(req));

        WebClockInResponse resp = service.approve(req.getId(), "ok", hrAdminEmail);

        assertEquals("APPROVED", resp.getStatus());
        verify(notificationService, times(1)).send(eq(employeeId), eq("WEB_CLOCK_IN_APPROVED"), any(), any(), any());
    }

    @Test
    void reject_notifiesOriginalRequesterWithReason() {
        WebClockInRequest req = pendingRequest();
        when(webClockInRepository.findById(req.getId())).thenReturn(Optional.of(req));

        WebClockInResponse resp = service.reject(req.getId(), "Not a valid work day", hrAdminEmail);

        assertEquals("REJECTED", resp.getStatus());
        verify(notificationService, times(1)).send(eq(employeeId), eq("WEB_CLOCK_IN_REJECTED"), any(),
                contains("Not a valid work day"), any());
    }

    @Test
    void reject_calledTwice_sendsNotificationOnlyOnce() {
        WebClockInRequest req = pendingRequest();
        when(webClockInRepository.findById(req.getId())).thenReturn(Optional.of(req));

        service.reject(req.getId(), null, hrAdminEmail);
        assertThrows(IllegalArgumentException.class, () -> service.reject(req.getId(), null, hrAdminEmail));

        verify(notificationService, times(1)).send(eq(employeeId), eq("WEB_CLOCK_IN_REJECTED"), any(), any(), any());
    }

    /**
     * Mirrors AttendanceService.checkOut_rejectsAndFlagsMissingCheckout_... — a Web Clock-In
     * session left open past its own workday/grace window (shiftDayCutover) must not be
     * accepted with a fabricated checkedOutAt/workedMinutes; it's flagged Missing Check-Out and
     * the click is rejected, same as the regular Check-In/Check-Out flow.
     */
    @Test
    void checkOut_rejectsAndFlagsMissingCheckout_whenPastItsGraceWindow() {
        String employeeEmail = "employee@test.com";
        Role empRole = Role.builder().id(2).code("EMPLOYEE").displayName("Employee").build();
        User empUser = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(empRole)).build();
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(empUser));
        lenient().when(attendanceProps.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(attendanceProps.getShiftDayCutover()).thenReturn(LocalTime.of(7, 0));

        LocalDate workDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(2);
        WebClockInRequest req = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .requestedCheckIn(LocalDateTime.of(workDate, LocalTime.of(17, 35)))
                .status("APPROVED")
                .build();
        when(webClockInRepository.findFirstByEmployeeUserIdAndStatusAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId, "APPROVED"))
                .thenReturn(Optional.of(req));

        Attendance record = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(LocalDateTime.of(workDate, LocalTime.of(17, 35)))
                .sessionStartedAt(LocalDateTime.of(workDate, LocalTime.of(17, 35)))
                .status("PRESENT")
                .build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, workDate))
                .thenReturn(Optional.of(record));

        assertThrows(IllegalArgumentException.class, () -> service.checkOut(employeeEmail, null));

        assertEquals("MISSING_CHECKOUT", record.getStatus());
        assertNull(record.getCheckOutAt());
        assertNull(record.getWorkedMinutes());
    }

    @Test
    void submit_succeeds_whenNoSessionIsCurrentlyOpen() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));
        when(attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.empty());

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Working from home").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("APPROVED", resp.getStatus());
    }

    /**
     * Only one session may be open at a time, however it started — the same rule
     * AttendanceService.checkIn already enforces for a second regular Check-In. This is NOT the
     * once-per-day restriction (see the next two tests for that).
     */
    @Test
    void submit_rejectsASecondConcurrentWebClockIn_whileASessionIsStillOpen() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Attendance openRecord = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(today)
                .checkInAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .sessionStartedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")))
                .status("PRESENT")
                .build();
        when(attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(openRecord));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Second try").timezone("Asia/Kolkata").build();

        assertThrows(IllegalArgumentException.class, () -> service.submit(req, employeeEmail));
    }

    /**
     * Requirement: Web Clock-In/Out is NOT restricted to once per day — an employee can
     * Web Clock-In, Web Clock-Out, then Web Clock-In again later the same day. The first cycle's
     * worked-minutes-so-far must be preserved (resumed, not reset) on the second cycle.
     */
    @Test
    void submit_allowsASecondWebClockInCycle_afterTheFirstCycleWasClosed() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

        // No session is currently OPEN — the first cycle was already checked out.
        when(attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.empty());

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Attendance closedRecord = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(today)
                .checkInAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(3))
                .checkOutAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusHours(1))
                .workedMinutes(120)
                .status("PRESENT")
                .build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(eq(employeeId), eq(today)))
                .thenReturn(Optional.of(closedRecord));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Back after lunch").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("APPROVED", resp.getStatus());
        // Resumed the SAME record — reopened (checkOutAt cleared) with the prior worked-minutes
        // total preserved, not reset to zero and not double-counted.
        assertNull(closedRecord.getCheckOutAt());
        assertEquals(120, closedRecord.getWorkedMinutes());
        assertEquals("WEB_REMOTE", closedRecord.getSource());
    }

    /**
     * A session left open past its own workday/grace window (a forgotten checkout from days ago)
     * must not block a fresh Web Clock-In forever — it gets flagged Missing Check-Out instead,
     * mirroring AttendanceService.checkIn's identical staleness bypass.
     */
    @Test
    void submit_flagsStaleOpenSessionAsMissingCheckout_andStillAllowsAFreshWebClockIn() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));
        lenient().when(attendanceProps.getShiftDayCutover()).thenReturn(LocalTime.of(7, 0));

        LocalDate staleWorkDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(2);
        Attendance staleOpenRecord = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(staleWorkDate)
                .checkInAt(LocalDateTime.of(staleWorkDate, LocalTime.of(17, 35)))
                .sessionStartedAt(LocalDateTime.of(staleWorkDate, LocalTime.of(17, 35)))
                .status("PRESENT")
                .build();
        when(attendanceRepository.findFirstByEmployeeUserIdAndCheckOutAtIsNullOrderByWorkDateDesc(employeeId))
                .thenReturn(Optional.of(staleOpenRecord));

        CreateWebClockInRequest req = CreateWebClockInRequest.builder().reason("Fresh remote day").timezone("Asia/Kolkata").build();

        WebClockInResponse resp = service.submit(req, employeeEmail);

        assertEquals("APPROVED", resp.getStatus());
        assertEquals("MISSING_CHECKOUT", staleOpenRecord.getStatus());
        assertNull(staleOpenRecord.getCheckOutAt());
    }

    /**
     * A Web Clock-In-opened session can also be closed through the OTHER entry point (a regular
     * Check-Out) — both share the same Attendance row. If the employee then clicks Web Clock Out
     * too (the section stays visible/actionable regardless of which entry point is "current" —
     * see AttendanceHeroBanner's WebClockInRow), this must sync the request to the record's
     * already-set checkOutAt rather than recomputing and double-counting a second, overlapping
     * session's worked minutes on top of what a regular Check-Out already counted.
     */
    @Test
    void checkOut_syncsRatherThanDoubleCounts_whenTheUnderlyingSessionWasAlreadyClosedByARegularCheckOut() {
        String employeeEmail = "employee@test.com";
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser(employeeEmail)));

        LocalDate workDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime checkInAt = LocalDateTime.of(workDate, LocalTime.of(10, 0));
        LocalDateTime regularCheckOutAt = LocalDateTime.of(workDate, LocalTime.of(12, 0));

        WebClockInRequest req = WebClockInRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .requestedCheckIn(checkInAt)
                .status("APPROVED")
                .build();
        when(webClockInRepository.findFirstByEmployeeUserIdAndStatusAndCheckedOutAtIsNullOrderByWorkDateDesc(employeeId, "APPROVED"))
                .thenReturn(Optional.of(req));

        // Already closed via a regular Check-Out — 120 worked minutes already counted once.
        Attendance record = Attendance.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .workDate(workDate)
                .checkInAt(checkInAt)
                .sessionStartedAt(checkInAt)
                .checkOutAt(regularCheckOutAt)
                .workedMinutes(120)
                .status("PRESENT")
                .build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, workDate))
                .thenReturn(Optional.of(record));

        WebClockInResponse resp = service.checkOut(employeeEmail, null);

        assertEquals(regularCheckOutAt, resp.getCheckedOutAt());
        // Untouched — no second session was recomputed on top of the one already counted.
        assertEquals(120, record.getWorkedMinutes());
        assertEquals(regularCheckOutAt, record.getCheckOutAt());
    }
}
