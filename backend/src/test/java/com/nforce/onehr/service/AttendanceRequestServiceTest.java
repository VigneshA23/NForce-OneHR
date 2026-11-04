package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.AttendanceRequestResponse;
import com.nforce.onehr.dto.attendance.CreateAttendanceRequest;
import com.nforce.onehr.entity.AttendanceRequest;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRequestRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests for Partial Day's monthly-hours cap (2h/month, spendable on any day(s)
 * within the month) and WFH's 2-day prior-notice rule. Both are hard limits enforced in submit()
 * — Partial Day's cap used to be advisory-only (see git history) but is now rejected outright,
 * same as WFH's monthly cap; getPartialDayBalance still reports usage-vs-cap for the UI's
 * "View Available Balance" display. Mirrors RegularizationServiceTest's isolation approach.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceRequestServiceTest {

    @Mock private AttendanceRequestRepository requestRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttendanceProperties attendanceProps;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    // The real seed, plus custom roles: one granted attendance_requests:APPROVE at TEAM, one at
    // ALL, and one granted only an unrelated module's APPROVE.
    @Spy private EffectivePermissionService effectivePermissionService = SeedMatrix.permissionService(Map.of(
            "CUSTOM_TEAM", Map.of("attendance_requests:APPROVE", "TEAM"),
            "CUSTOM_ALL", Map.of("attendance_requests:APPROVE", "ALL"),
            "CUSTOM_OTHER", Map.of("overtime:APPROVE", "ALL")));

    @InjectMocks private AttendanceRequestService service;

    private final UUID employeeId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";

    @BeforeEach
    void setUp() {
        Role employeeRole = Role.builder().id(1).code("EMPLOYEE").displayName("Employee").build();
        User employeeUser = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(employeeRole)).build();
        lenient().when(userRepository.findByEmail(employeeEmail)).thenReturn(java.util.Optional.of(employeeUser));
        lenient().when(requestRepository.save(any(AttendanceRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        // toResponse() looks these up defensively — absent is fine, it degrades to nulls/"Unknown".
        lenient().when(employeeRepository.findById(any())).thenReturn(java.util.Optional.empty());
        lenient().when(userRepository.findById(any())).thenReturn(java.util.Optional.empty());
        lenient().when(attendanceProps.getZone()).thenReturn("Asia/Kolkata");
    }

    private CreateAttendanceRequest partialDayRequest(LocalDate date, double hours) {
        return CreateAttendanceRequest.builder()
                .requestType("PARTIAL_DAY")
                .requestDate(date)
                .partialDayHours(BigDecimal.valueOf(hours))
                .partialDayMode("LATE_ARRIVE")
                .reason("test")
                .build();
    }

    private AttendanceRequest existing(LocalDate date, double hours, String status) {
        return existing(date, hours, "LATE_ARRIVE", status);
    }

    private AttendanceRequest existing(LocalDate date, double hours, String mode, String status) {
        return AttendanceRequest.builder()
                .id(UUID.randomUUID())
                .employeeUserId(employeeId)
                .requestType("PARTIAL_DAY")
                .requestDate(date)
                .partialDayHours(BigDecimal.valueOf(hours))
                .partialDayMode(mode)
                .status(status)
                .build();
    }

    @Test
    void allowsPartialDayRequestWithinTheAdvisoryAllowance() {
        AttendanceRequestResponse response = service.submit(partialDayRequest(LocalDate.of(2026, 8, 3), 2), employeeEmail);

        assertEquals(BigDecimal.valueOf(2.0), response.getPartialDayHours());
    }

    @Test
    void rejectsPartialDayRequestThatExceedsTheMonthlyCap() {
        // The 2h/month cap is a hard limit (see ATTENDANCE_POLICY: "not allowed to raise a
        // request for more than 120 minutes") — a single request larger than the whole cap is
        // rejected outright, not left for the approver to judge.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 3), employeeEmail));
        assertTrue(ex.getMessage().contains("not allowed to raise a request for more than 120 minutes"));
    }

    @Test
    void rejectsAnUnrealisticallyLargePartialDayValue() {
        // Regression for the "Will Come Late By" field accepting values like 999999999999
        // minutes with no upper bound — the monthly cap check must reject it outright, the same
        // way it rejects any other over-cap request, regardless of magnitude.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 999999999999.0), employeeEmail));
        assertTrue(ex.getMessage().contains("not allowed to raise a request for more than 120 minutes"));
    }

    @Test
    void rejectsZeroOrNegativePartialDayHours() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 0), employeeEmail));
        assertTrue(ex.getMessage().contains("greater than zero"));
    }

    @Test
    void wfhRequestsAreUnaffectedByThePartialDayAllowance() {
        CreateAttendanceRequest wfh = CreateAttendanceRequest.builder()
                .requestType("WFH")
                .requestDate(LocalDate.now().plusDays(10))
                .reason("test")
                .build();

        AttendanceRequestResponse response = service.submit(wfh, employeeEmail);

        assertNull(response.getPartialDayHours());
        // The same repository method backs both WFH's and Partial Day's monthly-cap lookup
        // (see wfhDaysUsedInMonth/partialDayHoursUsedInMonth) — it's called here for WFH's own
        // cap, just never with PARTIAL_DAY as the type.
        verify(requestRepository, never()).findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(any(), eq("PARTIAL_DAY"), any(), any());
    }

    // ---------------------------------------------------------------- Partial Day duplicate-slot blocking

    @Test
    void blocksPartialDayRequestWhenSameSlotIsPending() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDate(
                eq(employeeId), eq("PARTIAL_DAY"), eq(LocalDate.of(2026, 8, 9))))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 9), 1.0, "LATE_ARRIVE", "PENDING")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 1.0), employeeEmail));
        assertTrue(ex.getMessage().contains("pending or approved"));
    }

    @Test
    void blocksPartialDayRequestWhenSameSlotIsApproved() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDate(
                eq(employeeId), eq("PARTIAL_DAY"), eq(LocalDate.of(2026, 8, 9))))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 9), 1.0, "LATE_ARRIVE", "APPROVED")));

        assertThrows(IllegalArgumentException.class,
                () -> service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 1.0), employeeEmail));
    }

    @Test
    void allowsPartialDayRequestWhenSameSlotWasRejected() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDate(
                eq(employeeId), eq("PARTIAL_DAY"), eq(LocalDate.of(2026, 8, 9))))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 9), 1.0, "LATE_ARRIVE", "REJECTED")));

        AttendanceRequestResponse response = service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 1.0), employeeEmail);

        assertEquals(BigDecimal.valueOf(1.0), response.getPartialDayHours());
    }

    @Test
    void allowsPartialDayRequestWhenSameDateButDifferentMode() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDate(
                eq(employeeId), eq("PARTIAL_DAY"), eq(LocalDate.of(2026, 8, 9))))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 9), 1.0, "LEAVING_EARLY", "PENDING")));

        AttendanceRequestResponse response = service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 1.0), employeeEmail);

        assertEquals(BigDecimal.valueOf(1.0), response.getPartialDayHours());
    }

    @Test
    void allowsPartialDayRequestWhenSameDateAndModeButDifferentDuration() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDate(
                eq(employeeId), eq("PARTIAL_DAY"), eq(LocalDate.of(2026, 8, 9))))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 9), 2.0, "LATE_ARRIVE", "PENDING")));

        AttendanceRequestResponse response = service.submit(partialDayRequest(LocalDate.of(2026, 8, 9), 1.0), employeeEmail);

        assertEquals(BigDecimal.valueOf(1.0), response.getPartialDayHours());
    }

    // ---------------------------------------------------------------- WFH prior notice

    private CreateAttendanceRequest wfhRequest(LocalDate date) {
        return CreateAttendanceRequest.builder().requestType("WFH").requestDate(date).reason("test").build();
    }

    @Test
    void rejectsWfhRequestForToday() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(wfhRequest(LocalDate.now()), employeeEmail));
        assertEquals("WFH request requires 2 day(s) of prior notice.", ex.getMessage());
    }

    @Test
    void rejectsWfhRequestForTomorrow_oneDayIsNotEnoughNotice() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(wfhRequest(LocalDate.now().plusDays(1)), employeeEmail));
        assertEquals("WFH request requires 2 day(s) of prior notice.", ex.getMessage());
    }

    @Test
    void allowsWfhRequestExactlyTwoDaysOut() {
        AttendanceRequestResponse response = service.submit(wfhRequest(LocalDate.now().plusDays(2)), employeeEmail);
        assertEquals("WFH", response.getRequestType());
    }

    // ---------------------------------------------------------------- getPartialDayBalance

    @Test
    void balanceCountsPendingRequestsTowardUsage() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(
                eq(employeeId), eq("PARTIAL_DAY"), any(), any()))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 3), 1.0, "PENDING")));

        AttendanceRequestService.PartialDayBalance balance =
                service.getPartialDayBalance(employeeEmail, LocalDate.of(2026, 8, 20));

        assertEquals(BigDecimal.valueOf(1.0), balance.usedHours());
        assertEquals(BigDecimal.valueOf(1.0), balance.remainingHours());
    }

    @Test
    void balanceExcludesRejectedRequests() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(
                eq(employeeId), eq("PARTIAL_DAY"), any(), any()))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 3), 2.0, "REJECTED")));

        AttendanceRequestService.PartialDayBalance balance =
                service.getPartialDayBalance(employeeEmail, LocalDate.of(2026, 8, 20));

        assertEquals(0, balance.usedHours().compareTo(BigDecimal.ZERO));
        assertEquals(0, balance.remainingHours().compareTo(BigDecimal.valueOf(2.0)));
    }

    @Test
    void balanceReflectsApprovedUsageEvenPastTheAllowance() {
        when(requestRepository.findByEmployeeUserIdAndRequestTypeAndRequestDateBetween(
                eq(employeeId), eq("PARTIAL_DAY"), any(), any()))
                .thenReturn(List.of(existing(LocalDate.of(2026, 8, 3), 2.5, "APPROVED")));

        AttendanceRequestService.PartialDayBalance balance =
                service.getPartialDayBalance(employeeEmail, LocalDate.of(2026, 8, 20));

        assertEquals(BigDecimal.valueOf(2.5), balance.usedHours());
        assertEquals(BigDecimal.valueOf(0).setScale(1), balance.remainingHours().setScale(1));
    }

    // ---------------------------------------------------------------- approve/reject notifications (ONEHR-140)

    private final UUID hrAdminId = UUID.randomUUID();
    private final String hrAdminEmail = "hr@test.com";

    private void stubHrAdmin() {
        Role hrRole = Role.builder().id(2).code("HR_ADMIN").displayName("HR Admin").build();
        User hrUser = User.builder().id(hrAdminId).email(hrAdminEmail).roles(Set.of(hrRole)).build();
        lenient().when(userRepository.findByEmail(hrAdminEmail)).thenReturn(java.util.Optional.of(hrUser));
    }

    @Test
    void approve_notifiesOriginalRequester() {
        stubHrAdmin();
        AttendanceRequest pending = existing(LocalDate.of(2026, 8, 3), 2.0, "PENDING");
        when(requestRepository.findById(pending.getId())).thenReturn(java.util.Optional.of(pending));

        AttendanceRequestResponse resp = service.approve(pending.getId(), "Looks good", hrAdminEmail);

        assertEquals("APPROVED", resp.getStatus());
        verify(notificationService, times(1))
                .send(eq(employeeId), eq("ATTENDANCE_REQUEST_APPROVED"), any(), any(), any());
    }

    @Test
    void reject_notifiesOriginalRequesterWithReason() {
        stubHrAdmin();
        AttendanceRequest pending = existing(LocalDate.of(2026, 8, 3), 2.0, "PENDING");
        when(requestRepository.findById(pending.getId())).thenReturn(java.util.Optional.of(pending));

        AttendanceRequestResponse resp = service.reject(pending.getId(), "Team short-staffed", hrAdminEmail);

        assertEquals("REJECTED", resp.getStatus());
        verify(notificationService, times(1)).send(eq(employeeId), eq("ATTENDANCE_REQUEST_REJECTED"), any(),
                contains("Team short-staffed"), any());
    }

    @Test
    void approve_calledTwice_sendsNotificationOnlyOnce() {
        stubHrAdmin();
        AttendanceRequest pending = existing(LocalDate.of(2026, 8, 3), 2.0, "PENDING");
        when(requestRepository.findById(pending.getId())).thenReturn(java.util.Optional.of(pending));

        service.approve(pending.getId(), null, hrAdminEmail);
        assertThrows(IllegalArgumentException.class, () -> service.approve(pending.getId(), null, hrAdminEmail));

        verify(notificationService, times(1))
                .send(eq(employeeId), eq("ATTENDANCE_REQUEST_APPROVED"), any(), any(), any());
    }

    @Test
    void listTeamApprovedWfh_queriesOnlyApprovedWfhForTheManagersDirectReports() {
        LocalDate day = LocalDate.of(2026, 9, 24);
        UUID reportId = UUID.randomUUID();
        when(historyRepository.findCurrentDirectReportIds(employeeId)).thenReturn(List.of(reportId));
        when(requestRepository.findByEmployeeUserIdInAndRequestTypeAndStatusAndRequestDateBetween(
                List.of(reportId), "WFH", "APPROVED", day, day)).thenReturn(List.of());

        assertTrue(service.listTeamApprovedWfh(employeeEmail, day, day).isEmpty());
        verify(requestRepository).findByEmployeeUserIdInAndRequestTypeAndStatusAndRequestDateBetween(
                List.of(reportId), "WFH", "APPROVED", day, day);
    }

    @Test
    void listTeamApprovedWfh_noDirectReports_returnsEmptyWithoutQuerying() {
        LocalDate day = LocalDate.of(2026, 9, 24);
        when(historyRepository.findCurrentDirectReportIds(employeeId)).thenReturn(List.of());

        assertTrue(service.listTeamApprovedWfh(employeeEmail, day, day).isEmpty());
        verify(requestRepository, never()).findByEmployeeUserIdInAndRequestTypeAndStatusAndRequestDateBetween(
                any(), any(), any(), any(), any());
    }

    @Test
    void listPeerApprovedWfh_callerWithoutManager_returnsEmpty() {
        LocalDate day = LocalDate.of(2026, 9, 24);
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(java.util.Optional.empty());

        assertTrue(service.listPeerApprovedWfh(employeeEmail, day, day).isEmpty());
        verify(historyRepository, never()).findCurrentPeerIds(any());
    }

    // ---------------------------------------------------------------- permission-based access (custom roles)

    private static final List<String> CUSTOM = List.of("CUSTOM_TEAM", "CUSTOM_ALL", "CUSTOM_OTHER");

    /** "SUPER_ADMIN+MANAGER" -> those roles: system roles as seeded, custom roles with their own ids. */
    private static Set<Role> roles(String codes) {
        return Arrays.stream(codes.split("\\+"))
                .map(c -> CUSTOM.contains(c)
                        ? Role.builder().id(100 + CUSTOM.indexOf(c)).code(c).displayName(c).build()
                        : SeedMatrix.role(c))
                .collect(Collectors.toSet());
    }

    private User actorWith(String codes) {
        User actor = User.builder().id(UUID.randomUUID()).email("actor@test.com").roles(roles(codes)).build();
        when(userRepository.findByEmail("actor@test.com")).thenReturn(Optional.of(actor));
        return actor;
    }

    /** Pending, assigned to someone other than the actor. */
    private AttendanceRequest pendingAssignedElsewhere() {
        AttendanceRequest req = existing(LocalDate.of(2026, 8, 3), 1.0, "PENDING");
        req.setAssignedApproverId(UUID.randomUUID());
        return req;
    }

    private void assertReview(boolean allowed, AttendanceRequest req) {
        if (allowed) {
            assertEquals("APPROVED", service.approve(req.getId(), null, "actor@test.com").getStatus());
        } else {
            assertThrows(AccessDeniedException.class, () -> service.approve(req.getId(), null, "actor@test.com"));
        }
    }

    @ParameterizedTest
    @CsvSource({"HR_ADMIN,2", "SUPER_ADMIN,2", "CUSTOM_ALL,2", "MANAGER,1", "CUSTOM_TEAM,1", "CUSTOM_OTHER,1", "EMPLOYEE,1"})
    void listPendingForApprover_everyPendingRequestOnlyForApproveAllHolders(String codes, int expected) {
        User actor = actorWith(codes);
        AttendanceRequest assignedToActor = pendingAssignedElsewhere();
        assignedToActor.setAssignedApproverId(actor.getId());
        when(requestRepository.findByStatus("PENDING")).thenReturn(List.of(assignedToActor, pendingAssignedElsewhere()));

        assertEquals(expected, service.listPendingForApprover("actor@test.com").size());
    }

    @ParameterizedTest
    @CsvSource({"HR_ADMIN,true", "SUPER_ADMIN,true", "CUSTOM_ALL,true", "MANAGER,false", "CUSTOM_TEAM,false", "EMPLOYEE,false"})
    void approve_unrelatedRequest_onlyApproveAllHoldersMayReview(String codes, boolean allowed) {
        actorWith(codes);
        AttendanceRequest req = pendingAssignedElsewhere(); // and the actor isn't the requester's manager
        when(requestRepository.findById(req.getId())).thenReturn(Optional.of(req));
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        assertReview(allowed, req);
    }

    @ParameterizedTest
    @CsvSource({"MANAGER,true", "CUSTOM_TEAM,true", "CUSTOM_OTHER,false", "EMPLOYEE,false"})
    void approve_asRequestersCurrentManager_needsApproveTeam(String codes, boolean allowed) {
        User actor = actorWith(codes);
        AttendanceRequest req = pendingAssignedElsewhere();
        when(requestRepository.findById(req.getId())).thenReturn(Optional.of(req));
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.of(
                EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(actor.getId()).build()));

        assertReview(allowed, req);
    }

    @ParameterizedTest
    @CsvSource({"MANAGER,true", "HR_ADMIN,true", "SUPER_ADMIN,false", "EMPLOYEE,false", "SUPER_ADMIN+MANAGER,true",
            "CUSTOM_TEAM,true", "CUSTOM_ALL,true", "CUSTOM_OTHER,false", "SUPER_ADMIN+CUSTOM_TEAM,true"})
    void submit_explicitApprover_mustHoldApproveTeamOutsideSuperAdmin(String codes, boolean eligible) {
        User candidate = User.builder().id(UUID.randomUUID()).email("approver@test.com").roles(roles(codes)).build();
        when(userRepository.findById(candidate.getId())).thenReturn(Optional.of(candidate));
        CreateAttendanceRequest req = partialDayRequest(LocalDate.of(2026, 8, 3), 1.0);
        req.setManagerUserId(candidate.getId());

        if (eligible) {
            assertEquals(candidate.getId(), service.submit(req, employeeEmail).getAssignedApproverId());
        } else {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.submit(req, employeeEmail));
            assertEquals("Selected user is not an eligible approver", ex.getMessage());
        }
    }
}
