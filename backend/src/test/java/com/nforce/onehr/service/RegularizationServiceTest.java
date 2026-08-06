package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.attendance.CreateRegularizationRequest;
import com.nforce.onehr.dto.attendance.RegularizationResponse;
import com.nforce.onehr.entity.Attendance;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.RegularizationRequest;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.RegularizationApprovalRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests, mirroring LeaveServiceTest's isolation approach (this repo's H2
 * test profile can't create schema for the citext-typed entities, so tests avoid booting a
 * real ApplicationContext).
 */
@ExtendWith(MockitoExtension.class)
class RegularizationServiceTest {

    @Mock private RegularizationRequestRepository regularizationRepository;
    @Mock private RegularizationApprovalRepository regularizationApprovalRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private AttendanceProperties attendanceProps;

    @InjectMocks private RegularizationService regularizationService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final UUID hrId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final UUID superAdminId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final String managerEmail = "manager@test.com";
    private final String hrEmail = "hr@test.com";
    private final String strangerEmail = "stranger@test.com";
    private final String superAdminEmail = "superadmin@test.com";

    private User employeeUser;
    private User managerUser;
    private User hrUser;
    private User strangerUser;
    private User superAdminUser;

    @BeforeEach
    void setUp() throws Exception {
        Role managerRole = Role.builder().id(1).code("MANAGER").displayName("Manager").build();
        Role hrRole = Role.builder().id(2).code("HR_ADMIN").displayName("HR Admin").build();
        Role employeeRole = Role.builder().id(3).code("EMPLOYEE").displayName("Employee").build();
        Role superAdminRole = Role.builder().id(4).code("SUPER_ADMIN").displayName("Super Admin").build();

        employeeUser = User.builder().id(employeeId).email(employeeEmail).roles(Set.of(employeeRole)).build();
        managerUser = User.builder().id(managerId).email(managerEmail).roles(Set.of(managerRole)).build();
        hrUser = User.builder().id(hrId).email(hrEmail).roles(Set.of(hrRole)).build();
        strangerUser = User.builder().id(strangerId).email(strangerEmail).roles(Set.of(employeeRole)).build();
        // Per this org's setup, Super Admin accounts also hold EMPLOYEE, so they can submit
        // their own regularization requests (the submit endpoint is gated on hasRole('EMPLOYEE')).
        superAdminUser = User.builder().id(superAdminId).email(superAdminEmail)
                .roles(Set.of(superAdminRole, employeeRole)).build();

        lenient().when(userRepository.findById(employeeId)).thenReturn(Optional.of(employeeUser));
        lenient().when(userRepository.findById(managerId)).thenReturn(Optional.of(managerUser));
        lenient().when(userRepository.findById(hrId)).thenReturn(Optional.of(hrUser));
        lenient().when(userRepository.findById(strangerId)).thenReturn(Optional.of(strangerUser));
        lenient().when(userRepository.findById(superAdminId)).thenReturn(Optional.of(superAdminUser));
        lenient().when(employeeRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(regularizationApprovalRepository.findByRequestIdOrderByActionDateDesc(any()))
                .thenReturn(List.of());
        lenient().when(regularizationRepository.save(any(RegularizationRequest.class)))
                .thenAnswer(inv -> {
                    RegularizationRequest r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(UUID.randomUUID());
                    return r;
                });
        lenient().when(attendanceProps.getShiftStart()).thenReturn(LocalTime.of(9, 30));
        lenient().when(attendanceProps.getLateGraceMinutes()).thenReturn(15);
        lenient().when(attendanceProps.getHalfDayMaxHours()).thenReturn(4);
        // Matches the system default zone so LocalDate.now(ZoneId.of(...)) in the service
        // agrees with the plain LocalDate.now() used throughout these tests.
        lenient().when(attendanceProps.getZone()).thenReturn(java.time.ZoneId.systemDefault().getId());

        // @Value-injected fields — never populated outside a Spring container.
        Field employeeLookback = RegularizationService.class.getDeclaredField("employeeLookbackDays");
        employeeLookback.setAccessible(true);
        employeeLookback.set(regularizationService, 3);

        Field monthlyLimit = RegularizationService.class.getDeclaredField("monthlyLimit");
        monthlyLimit.setAccessible(true);
        monthlyLimit.set(regularizationService, 3);

        // Default for existing tests that don't care about the monthly cap — most submit()
        // tests use today's date and don't stub this, so let it resolve to a lenient 0.
        lenient().when(regularizationRepository.countByEmployeeUserIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(0L);
    }

    private CreateRegularizationRequest request(LocalDate date, LocalDateTime checkIn, LocalDateTime checkOut, String reason) {
        return CreateRegularizationRequest.builder()
                .attendanceDate(date).requestedCheckIn(checkIn).requestedCheckOut(checkOut).reason(reason).build();
    }

    @Test
    void submit_withNoManagerSelected_assignsCurrentManager() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        // assertNoDuplicateRequest checks APPROVED, then PARTIALLY_APPROVED, then PENDING —
        // all three need stubbing under strict-stub mode, not just the one this test cares about.
        lenient().when(regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(employeeId, LocalDate.now(), "PENDING"))
                .thenReturn(false);

        LocalDate today = LocalDate.now();
        RegularizationResponse resp = regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Forgot badge"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(managerId, resp.getAssignedApproverId());
        verify(auditService).log(employeeId, "REGULARIZATION_REQUESTED", employeeId);
    }

    @Test
    void submit_withSelectedEligibleManager_assignsSelection() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));

        LocalDate today = LocalDate.now();
        CreateRegularizationRequest req = request(today, today.atTime(9, 0), today.atTime(18, 0), "Missed punch");
        req.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals(hrId, resp.getAssignedApproverId());
        verify(historyRepository, never()).findByEmployeeUserIdAndEffectiveToIsNull(any());
    }

    @Test
    void submit_withIneligibleSelectedUser_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));

        LocalDate today = LocalDate.now();
        CreateRegularizationRequest req = request(today, today.atTime(9, 0), today.atTime(18, 0), "Missed punch");
        req.setManagerUserId(strangerId); // plain EMPLOYEE role — not an eligible approver

        assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(req, employeeEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_missingCheckOut_autoFillsCheckInFromExistingPunch() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        LocalDateTime existingCheckIn = today.atTime(9, 32);
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, today))
                .thenReturn(Optional.of(Attendance.builder().employeeUserId(employeeId).workDate(today)
                        .checkInAt(existingCheckIn).build()));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        // Client only supplies the missing check-out — check-in must be auto-filled server-side.
        CreateRegularizationRequest req = request(today, null, today.atTime(18, 30), "Forgot to punch out");
        RegularizationResponse resp = regularizationService.submit(req, employeeEmail);

        assertEquals(existingCheckIn, resp.getRequestedCheckIn());
        assertEquals(today.atTime(18, 30), resp.getRequestedCheckOut());
    }

    @Test
    void submit_whenApprovedRequestAlreadyExistsForDate_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        when(regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(employeeId, today, "APPROVED"))
                .thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Forgot badge"), employeeEmail));

        assertEquals("Already raised regularization for this date.", ex.getMessage());
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_whenPartiallyApprovedRequestAlreadyExistsForDate_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        // The APPROVED check runs first (and is unstubbed here — returns false by default);
        // only the PARTIALLY_APPROVED check this test targets needs a real stub.
        lenient().when(regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(employeeId, today, "APPROVED"))
                .thenReturn(false);
        when(regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(employeeId, today, "PARTIALLY_APPROVED"))
                .thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Forgot badge"), employeeEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_byNonSuperAdmin_beyond3DayWindow_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate tooOld = LocalDate.now().minusDays(4); // employeeLookbackDays is 3 in setUp()

        assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(tooOld, tooOld.atTime(9, 0), tooOld.atTime(18, 0), "Old correction"), employeeEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_bySuperAdmin_bypassesLookbackWindow() {
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        LocalDate tooOld = LocalDate.now().minusDays(10);

        RegularizationResponse resp = regularizationService.submit(
                request(tooOld, tooOld.atTime(9, 0), tooOld.atTime(18, 0), "Old correction"), superAdminEmail);

        assertEquals("PENDING", resp.getStatus());
    }

    @Test
    void submit_byNonSuperAdmin_atMonthlyLimit_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        when(regularizationRepository.countByEmployeeUserIdAndCreatedAtBetween(eq(employeeId), any(), any()))
                .thenReturn(3L); // monthlyLimit is 3 in setUp()

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Another one"), employeeEmail));

        assertTrue(ex.getMessage().contains("maximum"));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void submit_bySuperAdmin_exemptFromMonthlyLimit() {
        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        LocalDate today = LocalDate.now();

        RegularizationResponse resp = regularizationService.submit(
                request(today, today.atTime(9, 0), today.atTime(18, 0), "Yet another"), superAdminEmail);

        assertEquals("PENDING", resp.getStatus());
        verify(regularizationRepository, never()).countByEmployeeUserIdAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void update_doesNotConsumeMonthlyLimitSlot() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("PENDING").build();

        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        regularizationService.update(pending.getId(),
                request(date, date.atTime(9, 15), date.atTime(18, 15), "Updated reason"), employeeEmail);

        verify(regularizationRepository, never()).countByEmployeeUserIdAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void submit_bothTimesMissingWithNoExistingPunch_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        LocalDate today = LocalDate.now();
        // resolveTimes() rejects a both-null request before ever consulting attendanceRepository,
        // so no stub is needed for it here.

        assertThrows(IllegalArgumentException.class,
                () -> regularizationService.submit(request(today, null, null, "Nothing on file"), employeeEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void approve_byAssignedManager_transitionsToPartiallyApproved() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        RegularizationResponse resp = regularizationService.approve(pending.getId(), null, managerEmail);

        assertEquals("PARTIALLY_APPROVED", resp.getStatus());
        assertEquals(managerId, pending.getReviewedBy());
        assertEquals(managerId, pending.getApprovedBy());
        assertNotNull(pending.getApprovedAt());
        assertNull(pending.getFinalApprovedBy());
        // Manager stage never touches the attendance record — only final approval does.
        verify(attendanceRepository, never()).save(any());
        verify(regularizationApprovalRepository).save(argThat(a ->
                a.getRequestId().equals(pending.getId()) && a.getActionType().equals("APPROVED")
                        && a.getActionBy().equals(managerId) && "MANAGER".equals(a.getActorRole())));
        verify(auditService).log(managerId, "REGULARIZATION_PARTIALLY_APPROVED", employeeId);
    }

    @Test
    void approve_byManagerNotAssigned_isDenied() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        // strangerUser holds only EMPLOYEE — approve()'s status-first branching denies it before
        // ever reaching assertCanReview's historyRepository fallback, so no stub needed there.

        assertThrows(AccessDeniedException.class, () -> regularizationService.approve(pending.getId(), null, strangerEmail));
        verify(regularizationRepository, never()).save(any());
        verify(regularizationApprovalRepository, never()).save(any());
    }

    @Test
    void approve_byHrAdmin_onPending_isDenied() {
        // HR_ADMIN is a final-stage-only approver — must wait for the manager stage first.
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        // HR_ADMIN isn't a recognized actor for the PENDING stage (only MANAGER/SUPER_ADMIN are) —
        // denied the same way an unrelated stranger would be.
        assertThrows(AccessDeniedException.class, () -> regularizationService.approve(pending.getId(), null, hrEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void approve_byHrAdmin_onPartiallyApproved_transitionsToApproved() {
        LocalDate date = LocalDate.now();
        RegularizationRequest partiallyApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PARTIALLY_APPROVED")
                .approvedBy(managerId).approvedAt(LocalDateTime.now()).build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(partiallyApproved.getId())).thenReturn(Optional.of(partiallyApproved));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        RegularizationResponse resp = regularizationService.approve(partiallyApproved.getId(), null, hrEmail);

        assertEquals("APPROVED", resp.getStatus());
        assertEquals(hrId, partiallyApproved.getFinalApprovedBy());
        assertNotNull(partiallyApproved.getFinalApprovedAt());
        // Stage 1 fields set earlier must survive the stage-2 transition untouched.
        assertEquals(managerId, partiallyApproved.getApprovedBy());
        verify(regularizationApprovalRepository).save(argThat(a -> "HR_ADMIN".equals(a.getActorRole())));
        verify(auditService).log(hrId, "REGULARIZATION_APPROVED", employeeId);
    }

    @Test
    void approve_bySuperAdmin_bypassesFromPending_directlyToApproved() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(superAdminEmail)).thenReturn(Optional.of(superAdminUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attendanceRepository.findByEmployeeUserIdAndWorkDate(employeeId, date)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));

        RegularizationResponse resp = regularizationService.approve(pending.getId(), null, superAdminEmail);

        assertEquals("APPROVED", resp.getStatus());
        assertNull(pending.getApprovedBy()); // bypass skips the manager stage entirely
        assertEquals(superAdminId, pending.getFinalApprovedBy());
        verify(regularizationApprovalRepository).save(argThat(a -> "SUPER_ADMIN".equals(a.getActorRole())));
    }

    @Test
    void reject_recordsCommentAndAuditRow() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        RegularizationResponse resp = regularizationService.reject(pending.getId(), "Not a valid correction", managerEmail);

        assertEquals("REJECTED", resp.getStatus());
        assertEquals("Not a valid correction", resp.getReviewComment());
        verify(regularizationApprovalRepository).save(argThat(a ->
                a.getActionType().equals("REJECTED") && "Not a valid correction".equals(a.getComments())
                        && "MANAGER".equals(a.getActorRole())));
    }

    @Test
    void reject_byHrAdmin_onPending_isDenied() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PENDING").build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThrows(AccessDeniedException.class, () -> regularizationService.reject(pending.getId(), "No", hrEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void reject_byHrAdmin_onPartiallyApproved_succeeds() {
        LocalDate date = LocalDate.now();
        RegularizationRequest partiallyApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Missed punch").status("PARTIALLY_APPROVED").build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findById(partiallyApproved.getId())).thenReturn(Optional.of(partiallyApproved));

        RegularizationResponse resp = regularizationService.reject(partiallyApproved.getId(), "Insufficient evidence", hrEmail);

        assertEquals("REJECTED", resp.getStatus());
        verify(regularizationApprovalRepository).save(argThat(a -> "HR_ADMIN".equals(a.getActorRole())));
    }

    @Test
    void update_byOwnerWhilePending_reResolvesApprover() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("PENDING").build();

        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        CreateRegularizationRequest edit = request(date, date.atTime(9, 15), date.atTime(18, 15), "Updated reason");
        edit.setManagerUserId(hrId);

        RegularizationResponse resp = regularizationService.update(pending.getId(), edit, employeeEmail);

        assertEquals("Updated reason", resp.getReason());
        assertEquals(hrId, resp.getAssignedApproverId());
        assertEquals(date.atTime(9, 15), resp.getRequestedCheckIn());
    }

    @Test
    void update_byNonOwner_isDenied() {
        LocalDate date = LocalDate.now();
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("PENDING").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThrows(AccessDeniedException.class, () -> regularizationService.update(
                pending.getId(), request(date, date.atTime(9, 15), date.atTime(18, 15), "Hijack"), strangerEmail));
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void update_whenMovedToDateWithApprovedRequest_isRejected() {
        LocalDate date = LocalDate.now();
        // Must stay within the 3-day lookback window and not be in the future — otherwise
        // validateLookbackWindow rejects it before the duplicate-date check this test targets.
        LocalDate approvedDate = date.minusDays(1);
        RegularizationRequest pending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("PENDING").build();

        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(regularizationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(regularizationRepository.existsByEmployeeUserIdAndAttendanceDateAndStatus(employeeId, approvedDate, "APPROVED"))
                .thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> regularizationService.update(
                pending.getId(), request(approvedDate, approvedDate.atTime(9, 15), approvedDate.atTime(18, 15), "Moved"), employeeEmail));

        assertEquals("Already raised regularization for this date.", ex.getMessage());
        verify(regularizationRepository, never()).save(any());
    }

    @Test
    void update_afterAlreadyDecided_isRejected() {
        LocalDate date = LocalDate.now();
        RegularizationRequest decided = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(date)
                .requestedCheckIn(date.atTime(9, 0)).requestedCheckOut(date.atTime(18, 0))
                .reason("Old reason").status("APPROVED").build();

        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(regularizationRepository.findById(decided.getId())).thenReturn(Optional.of(decided));

        assertThrows(IllegalStateException.class, () -> regularizationService.update(
                decided.getId(), request(date, date.atTime(9, 15), date.atTime(18, 15), "Too late"), employeeEmail));
    }

    @Test
    void listPendingForApprover_managerSeesOnlyAssignedRequests() {
        RegularizationRequest assigned = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("x").status("PENDING").build();
        RegularizationRequest notAssigned = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(strangerId).assignedApproverId(hrId).attendanceDate(LocalDate.now())
                .reason("y").status("PENDING").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findByStatus("PENDING")).thenReturn(List.of(assigned, notAssigned));

        List<RegularizationResponse> queue = regularizationService.listPendingForApprover(managerEmail);

        assertEquals(1, queue.size());
        assertEquals(assigned.getId(), queue.get(0).getId());
    }

    @Test
    void listPendingForApprover_hrAdminSeesOnlyPartiallyApproved() {
        // HR is a final-stage-only approver — their queue is PARTIALLY_APPROVED, not PENDING.
        RegularizationRequest partiallyApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("x").status("PARTIALLY_APPROVED").build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findByStatus("PARTIALLY_APPROVED")).thenReturn(List.of(partiallyApproved));

        List<RegularizationResponse> queue = regularizationService.listPendingForApprover(hrEmail);

        assertEquals(1, queue.size());
        assertEquals(partiallyApproved.getId(), queue.get(0).getId());
        verify(regularizationRepository, never()).findByStatus("PENDING");
    }

    @Test
    void listPendingForApprover_dualRoleManagerAndHrAdmin_seesBothQueues() {
        RegularizationRequest assignedPending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("x").status("PENDING").build();
        RegularizationRequest partiallyApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(strangerId).assignedApproverId(hrId).attendanceDate(LocalDate.now())
                .reason("y").status("PARTIALLY_APPROVED").build();
        User dualRoleUser = User.builder().id(managerId).email(managerEmail)
                .roles(Set.of(
                        Role.builder().id(1).code("MANAGER").displayName("Manager").build(),
                        Role.builder().id(2).code("HR_ADMIN").displayName("HR Admin").build()))
                .build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(dualRoleUser));
        when(regularizationRepository.findByStatus("PENDING")).thenReturn(List.of(assignedPending));
        when(regularizationRepository.findByStatus("PARTIALLY_APPROVED")).thenReturn(List.of(partiallyApproved));

        List<RegularizationResponse> queue = regularizationService.listPendingForApprover(managerEmail);

        assertEquals(2, queue.size());
        assertTrue(queue.stream().map(RegularizationResponse::getId)
                .toList().containsAll(List.of(assignedPending.getId(), partiallyApproved.getId())));
    }

    @Test
    void listForApprover_managerSeesOnlyAssignedRequestsAcrossAllStatuses() {
        RegularizationRequest assignedPending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("x").status("PENDING").createdAt(LocalDateTime.now()).build();
        RegularizationRequest assignedApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now().minusDays(1))
                .reason("y").status("APPROVED").createdAt(LocalDateTime.now().minusDays(1)).build();
        RegularizationRequest notAssignedRejected = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(strangerId).assignedApproverId(hrId).attendanceDate(LocalDate.now())
                .reason("z").status("REJECTED").createdAt(LocalDateTime.now()).build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(regularizationRepository.findAll()).thenReturn(List.of(assignedPending, assignedApproved, notAssignedRejected));

        List<RegularizationResponse> all = regularizationService.listForApprover(managerEmail);

        assertEquals(2, all.size());
        assertTrue(all.stream().map(RegularizationResponse::getId)
                .toList().containsAll(List.of(assignedPending.getId(), assignedApproved.getId())));
    }

    @Test
    void listForApprover_hrAdminSeesEveryRequestRegardlessOfAssignee() {
        RegularizationRequest assignedToManagerPending = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(employeeId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("x").status("PENDING").createdAt(LocalDateTime.now()).build();
        RegularizationRequest assignedToManagerApproved = RegularizationRequest.builder().id(UUID.randomUUID())
                .employeeUserId(strangerId).assignedApproverId(managerId).attendanceDate(LocalDate.now())
                .reason("y").status("APPROVED").createdAt(LocalDateTime.now()).build();

        when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser));
        when(regularizationRepository.findAll())
                .thenReturn(List.of(assignedToManagerPending, assignedToManagerApproved));

        List<RegularizationResponse> all = regularizationService.listForApprover(hrEmail);

        assertEquals(2, all.size());
    }

    @Test
    void listApprovers_returnsEligibleRoleEmployeesOnly() {
        Employee managerEmployee = Employee.builder().userId(managerId).fullName("Manager One").user(managerUser).build();
        when(employeeRepository.findActiveByRoleCodes(Set.of("MANAGER", "HR_ADMIN")))
                .thenReturn(List.of(managerEmployee));

        List<?> approvers = regularizationService.listApprovers();

        assertEquals(1, approvers.size());
    }
}
