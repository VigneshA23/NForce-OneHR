package com.nforce.onehr.service;

import com.nforce.onehr.dto.CreateLeaveRequestRequest;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests — deliberately avoid @SpringBootTest/H2 here. The
 * repo's H2 test profile predates this change and can't create schema for the
 * citext-typed entities (User/Department/Designation/Location), so any test
 * that boots the real ApplicationContext against it fails on unrelated tables.
 * Fixing that is a separate, app-wide concern; this suite tests LeaveService
 * in isolation with mocked repositories instead.
 */
@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private LeaveBalanceRepository leaveBalanceRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private NotificationService notificationService;

    @InjectMocks private LeaveService leaveService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final String managerEmail = "manager@test.com";
    private final String strangerEmail = "stranger@test.com";

    private User employeeUser;
    private User managerUser;
    private User strangerUser;
    private LeaveType annual;

    @BeforeEach
    void setUp() {
        employeeUser = User.builder().id(employeeId).email(employeeEmail).build();
        managerUser = User.builder().id(managerId).email(managerEmail).build();
        strangerUser = User.builder().id(strangerId).email(strangerEmail).build();
        annual = LeaveType.builder().id(UUID.randomUUID()).code("ANNUAL").name("Annual Leave").build();

        // employeeName() falls back to userRepository when there's no Employee row —
        // stub loosely (lenient) so tests that don't inspect names don't need it repeated.
        lenient().when(employeeRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(userRepository.findById(employeeId)).thenReturn(Optional.of(employeeUser));
        lenient().when(userRepository.findById(managerId)).thenReturn(Optional.of(managerUser));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
    }

    private CreateLeaveRequestRequest request(LocalDate start, LocalDate end, boolean halfDay, String reason) {
        CreateLeaveRequestRequest req = new CreateLeaveRequestRequest();
        req.setLeaveTypeCode("ANNUAL");
        req.setStartDate(start);
        req.setEndDate(end);
        req.setHalfDay(halfDay);
        req.setReason(reason);
        return req;
    }

    private LeaveBalance balanceOf(BigDecimal total, BigDecimal used) {
        return LeaveBalance.builder().employeeUserId(employeeId).leaveType(annual)
                .year(LocalDate.now().getYear()).totalDays(total).usedDays(used).build();
    }

    @Test
    void submitRequest_createsPendingRequest_withoutTouchingBalance() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> {
            LeaveRequest r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        LocalDate start = LocalDate.now().plusDays(5);
        LeaveRequestResponse resp = leaveService.submitRequest(request(start, start.plusDays(2), false, "Vacation"), employeeEmail);

        assertEquals("PENDING", resp.getStatus());
        assertEquals(new BigDecimal("3"), resp.getTotalDays()); // inclusive day count
        verify(leaveBalanceRepository, never()).save(any());
        verify(auditService).log(employeeId, "LEAVE_REQUEST_SUBMITTED", resp.getId());
    }

    @Test
    void submitRequest_halfDay_countsAsHalfDay() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("20"), BigDecimal.ZERO)));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate day = LocalDate.now().plusDays(1);
        LeaveRequestResponse resp = leaveService.submitRequest(request(day, day, true, "Doctor"), employeeEmail);

        assertEquals(new BigDecimal("0.5"), resp.getTotalDays());
    }

    @Test
    void submitRequest_halfDayAcrossMultipleDates_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));

        LocalDate start = LocalDate.now();
        assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(start, start.plusDays(1), true, "x"), employeeEmail));
    }

    @Test
    void submitRequest_exceedingBalance_isRejected() {
        when(userRepository.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(leaveTypeRepository.findByCode("ANNUAL")).thenReturn(Optional.of(annual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balanceOf(new BigDecimal("2"), BigDecimal.ZERO)));

        LocalDate start = LocalDate.now();
        assertThrows(IllegalArgumentException.class,
                () -> leaveService.submitRequest(request(start, start.plusDays(5), false, "Too long"), employeeEmail));
        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void approve_byCurrentManager_decrementsBalanceAndRecordsDecision() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("4")).status("PENDING").employeeReason("Trip").build();
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LeaveRequestResponse approved = leaveService.approve(pending.getId(), managerEmail);

        assertEquals("APPROVED", approved.getStatus());
        assertEquals(managerId, pending.getDecidedBy());
        assertNotNull(approved.getDecidedAt());
        assertEquals(new BigDecimal("4"), balance.getUsedDays());
        verify(leaveBalanceRepository).save(balance);
        verify(auditService).log(eq(managerId), eq("LEAVE_REQUEST_APPROVED"), eq(pending.getId()), any(), any());
        verify(notificationService, times(1)).send(eq(employeeId), eq("LEAVE_APPROVED"), any(), any(), any());
    }

    @Test
    void reject_byCurrentManager_requiresReasonAndLeavesBalanceUntouched() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LeaveRequestResponse rejected = leaveService.reject(pending.getId(), "Team coverage conflict", managerEmail);

        assertEquals("REJECTED", rejected.getStatus());
        assertEquals("Team coverage conflict", rejected.getDecisionReason());
        verify(leaveBalanceRepository, never()).save(any());
        verify(auditService).log(eq(managerId), eq("LEAVE_REQUEST_REJECTED"), eq(pending.getId()), any(), any());
        verify(notificationService, times(1)).send(eq(employeeId), eq("LEAVE_REJECTED"), any(),
                contains("Team coverage conflict"), any());
    }

    @Test
    void approve_byNonManager_isDenied() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));

        assertThrows(AccessDeniedException.class, () -> leaveService.approve(pending.getId(), strangerEmail));
        verify(leaveBalanceRepository, never()).save(any());
        verify(leaveRequestRepository, never()).save(any());
        verify(notificationService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void approve_withNoManagerRelationship_isDenied() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> leaveService.approve(pending.getId(), managerEmail));
    }

    @Test
    void approve_alreadyDecided_isRejected() {
        LeaveRequest decided = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("APPROVED").employeeReason("Trip").build();

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(decided.getId())).thenReturn(Optional.of(decided));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));

        assertThrows(IllegalStateException.class, () -> leaveService.approve(decided.getId(), managerEmail));
        verify(leaveBalanceRepository, never()).save(any());
    }

    /**
     * A second approve() call on an already-decided request must not fire a second
     * notification (ONEHR-140) — the PENDING guard in approve() blocks it before the
     * notification call is ever reached.
     */
    @Test
    void approve_calledTwice_sendsNotificationOnlyOnce() {
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);

        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        leaveService.approve(pending.getId(), managerEmail);
        assertThrows(IllegalStateException.class, () -> leaveService.approve(pending.getId(), managerEmail));

        verify(notificationService, times(1)).send(eq(employeeId), eq("LEAVE_APPROVED"), any(), any(), any());
    }

    @Test
    void listPendingApprovals_isScopedToCurrentDirectReportsOnly() {
        when(userRepository.findByEmail(managerEmail)).thenReturn(Optional.of(managerUser));
        when(historyRepository.findByManagerUserIdAndEffectiveToIsNull(managerId))
                .thenReturn(List.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();
        when(leaveRequestRepository.findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(List.of(employeeId), "PENDING"))
                .thenReturn(List.of(pending));

        List<LeaveRequestResponse> queue = leaveService.listPendingApprovals(managerEmail);

        assertEquals(1, queue.size());
        assertEquals(pending.getId(), queue.get(0).getId());
    }

    @Test
    void listPendingApprovals_withNoDirectReports_returnsEmptyWithoutQueryingRequests() {
        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(historyRepository.findByManagerUserIdAndEffectiveToIsNull(strangerId)).thenReturn(List.of());

        List<LeaveRequestResponse> queue = leaveService.listPendingApprovals(strangerEmail);

        assertTrue(queue.isEmpty());
        verify(leaveRequestRepository, never()).findByEmployeeUserIdInAndStatusOrderByCreatedAtAsc(any(), any());
    }

    // ── HR Admin / Super Admin override (not the employee's reporting manager) ──

    private User userWithRole(UUID id, String email, String roleCode) {
        Role role = Role.builder().id(1).code(roleCode).displayName(roleCode).build();
        return User.builder().id(id).email(email).roles(Set.of(role)).build();
    }

    @Test
    void listPendingApprovals_forHrAdmin_returnsAllPendingRegardlessOfReportingLine() {
        User hrAdmin = userWithRole(strangerId, strangerEmail, "HR_ADMIN");
        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(hrAdmin));
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();
        when(leaveRequestRepository.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(pending));

        List<LeaveRequestResponse> queue = leaveService.listPendingApprovals(strangerEmail);

        assertEquals(1, queue.size());
        assertEquals(pending.getId(), queue.get(0).getId());
        verify(historyRepository, never()).findByManagerUserIdAndEffectiveToIsNull(any());
    }

    @Test
    void listPendingApprovals_forSuperAdmin_returnsAllPendingRegardlessOfReportingLine() {
        User superAdmin = userWithRole(strangerId, strangerEmail, "SUPER_ADMIN");
        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(superAdmin));
        when(leaveRequestRepository.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of());

        List<LeaveRequestResponse> queue = leaveService.listPendingApprovals(strangerEmail);

        assertTrue(queue.isEmpty());
        verify(historyRepository, never()).findByManagerUserIdAndEffectiveToIsNull(any());
    }

    @Test
    void approve_byHrAdmin_whoIsNotTheReportingManager_isAllowed() {
        User hrAdmin = userWithRole(strangerId, strangerEmail, "HR_ADMIN");
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("2")).status("PENDING").employeeReason("Trip").build();
        LeaveBalance balance = balanceOf(new BigDecimal("20"), BigDecimal.ZERO);

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(hrAdmin));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(eq(employeeId), eq(annual.getId()), any()))
                .thenReturn(Optional.of(balance));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LeaveRequestResponse approved = leaveService.approve(pending.getId(), strangerEmail);

        assertEquals("APPROVED", approved.getStatus());
        assertEquals(strangerId, pending.getDecidedBy());
        assertEquals(new BigDecimal("2"), balance.getUsedDays());
        // Admin override must not even need to resolve the reporting-manager relationship.
        verify(historyRepository, never()).findByEmployeeUserIdAndEffectiveToIsNull(any());
        verify(auditService).log(eq(strangerId), eq("LEAVE_REQUEST_APPROVED"), eq(pending.getId()), any(), any());
    }

    @Test
    void reject_bySuperAdmin_whoIsNotTheReportingManager_isAllowed() {
        User superAdmin = userWithRole(strangerId, strangerEmail, "SUPER_ADMIN");
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(superAdmin));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LeaveRequestResponse rejected = leaveService.reject(pending.getId(), "Policy conflict", strangerEmail);

        assertEquals("REJECTED", rejected.getStatus());
        verify(leaveBalanceRepository, never()).save(any());
        verify(historyRepository, never()).findByEmployeeUserIdAndEffectiveToIsNull(any());
    }

    @Test
    void approve_byEmployeeLevelUser_withNoOverrideRoleAndNotTheManager_isDenied() {
        // strangerUser deliberately carries no roles (see setUp) — same shape as a plain
        // EMPLOYEE-level account: no HR_ADMIN/SUPER_ADMIN override and not the current manager.
        LeaveRequest pending = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(annual).startDate(LocalDate.now()).endDate(LocalDate.now())
                .totalDays(new BigDecimal("1")).status("PENDING").employeeReason("Trip").build();

        when(userRepository.findByEmail(strangerEmail)).thenReturn(Optional.of(strangerUser));
        when(leaveRequestRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(employeeId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(employeeId).managerUserId(managerId).build()));

        assertThrows(AccessDeniedException.class, () -> leaveService.approve(pending.getId(), strangerEmail));
        verify(leaveBalanceRepository, never()).save(any());
        verify(leaveRequestRepository, never()).save(any());
    }
}
