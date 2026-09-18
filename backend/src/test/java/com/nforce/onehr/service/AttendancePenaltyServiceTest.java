package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.attendance.AttendancePenaltyResponse;
import com.nforce.onehr.dto.attendance.PenaltyCancelResultResponse;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.RegularizationRequest;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import com.nforce.onehr.repository.RegularizationRequestRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Manager: Regularize & Cancel Penalties. Pure Mockito, same isolation approach as
 * AttendanceServiceTeamStatsTest — {@link AttendancePenaltyRepository#findAll(org.springframework.data.jpa.domain.Specification)}
 * is stubbed directly rather than exercising real Specification predicates (no DB in this test layer).
 */
@ExtendWith(MockitoExtension.class)
class AttendancePenaltyServiceTest {

    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private RegularizationRequestRepository regularizationRequestRepository;
    @Mock private AuditService auditService;
    @Mock private LeaveBalanceRepository leaveBalanceRepository;
    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private NotificationService notificationService;
    @Mock private EmployeeService employeeService;
    @Mock private UserRepository userRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Spy private AuditSnapshotSerializer auditSnapshot = new AuditSnapshotSerializer(new ObjectMapper());

    @InjectMocks private AttendancePenaltyService service;

    private final UUID managerId = UUID.randomUUID();
    private final UUID empId = UUID.randomUUID();
    private final UUID otherEmpId = UUID.randomUUID();
    private final String managerEmail = "manager@test.com";
    private final LocalDate incidentDate = LocalDate.of(2026, 8, 3);
    private final LocalDate from = LocalDate.of(2026, 8, 1);
    private final LocalDate to = LocalDate.of(2026, 8, 10);

    private Employee manager;
    private Employee employee;

    @BeforeEach
    void setUp() {
        manager = Employee.builder().userId(managerId).fullName("Manager One").build();
        employee = Employee.builder().userId(empId).fullName("Employee One").employeeCode("NF-1").build();
        lenient().when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(manager));
        lenient().when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of(empId));
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(List.of(empId))).thenReturn(List.of(employee));
        lenient().when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        // Default: the conditional status transition succeeds (affects exactly one row) — tests
        // for the "lost the concurrency race" case override this explicitly to return 0.
        lenient().when(attendancePenaltyRepository.transitionStatus(any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    private AttendancePenalty penalty(String status) {
        return AttendancePenalty.builder().id(UUID.randomUUID()).employeeUserId(empId)
                .incidentDate(incidentDate).discrepancyType("LATE_ARRIVAL").status(status)
                .evaluatedAt(LocalDateTime.now()).penalizedOn(LocalDateTime.now()).build();
    }

    @Test
    void list_hrAdmin_seesOrgWideScope_notLimitedToOwnDirectReports() {
        // HR_ADMIN with zero direct reports of their own (the common case) — before the fix this
        // would return an empty list even though the controller authorizes HR_ADMIN.
        Role hrAdminRole = Role.builder().code("HR_ADMIN").build();
        User hrAdminUser = User.builder().roles(java.util.Set.of(hrAdminRole)).build();
        Employee hrAdmin = Employee.builder().userId(managerId).fullName("HR One").user(hrAdminUser).build();
        when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(hrAdmin));
        when(userRepository.findEmployeeRoleUserIds()).thenReturn(java.util.Set.of(otherEmpId));
        when(employeeRepository.findAllByIdWithScheduleDetails(List.of(otherEmpId)))
                .thenReturn(List.of(Employee.builder().userId(otherEmpId).fullName("Other Employee").employeeCode("NF-2").build()));
        AttendancePenalty p = AttendancePenalty.builder().id(UUID.randomUUID()).employeeUserId(otherEmpId)
                .incidentDate(incidentDate).discrepancyType("LATE_ARRIVAL").status(AttendancePenaltyStatus.PENDING_REVIEW)
                .evaluatedAt(LocalDateTime.now()).penalizedOn(LocalDateTime.now()).build();
        when(attendancePenaltyRepository.findAll((org.springframework.data.jpa.domain.Specification<AttendancePenalty>) any()))
                .thenReturn(List.of(p));

        List<AttendancePenaltyResponse> result = service.list(managerEmail, from, to, null, null, null, null, null);

        assertEquals(1, result.size());
        assertEquals(otherEmpId, result.get(0).getEmployeeUserId());
        verify(managerHistoryRepository, org.mockito.Mockito.never()).findCurrentDirectReportIds(any());
    }

    @Test
    void cancelBulk_hrAdmin_canCancelOutsideOwnDirectReports() {
        Role hrAdminRole = Role.builder().code("SUPER_ADMIN").build();
        User hrAdminUser = User.builder().roles(java.util.Set.of(hrAdminRole)).build();
        Employee superAdmin = Employee.builder().userId(managerId).fullName("Super One").user(hrAdminUser).build();
        when(employeeRepository.findByUser_Email(managerEmail)).thenReturn(Optional.of(superAdmin));
        when(userRepository.findEmployeeRoleUserIds()).thenReturn(java.util.Set.of(otherEmpId));
        AttendancePenalty p = AttendancePenalty.builder().id(UUID.randomUUID()).employeeUserId(otherEmpId)
                .incidentDate(incidentDate).discrepancyType("LATE_ARRIVAL").status(AttendancePenaltyStatus.PENDING_REVIEW)
                .evaluatedAt(LocalDateTime.now()).penalizedOn(LocalDateTime.now()).build();
        when(attendancePenaltyRepository.findById(p.getId())).thenReturn(Optional.of(p));

        PenaltyCancelResultResponse result = service.cancelBulk(managerEmail, List.of(p.getId()), "policy waived");

        assertEquals(List.of(p.getId()), result.getSucceededIds());
        assertTrue(result.getFailed().isEmpty());
    }

    @Test
    void list_noDirectReports_returnsEmpty() {
        when(managerHistoryRepository.findCurrentDirectReportIds(managerId)).thenReturn(List.of());

        List<AttendancePenaltyResponse> result = service.list(managerEmail, from, to, null, null, null, null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void list_excludesPenaltiesWithAnActiveRegularization() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findAll((org.springframework.data.jpa.domain.Specification<AttendancePenalty>) any()))
                .thenReturn(List.of(p));
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(List.of(empId), from, to))
                .thenReturn(List.of(RegularizationRequest.builder()
                        .employeeUserId(empId).attendanceDate(incidentDate).status("PENDING").reason("x").build()));

        List<AttendancePenaltyResponse> result = service.list(managerEmail, from, to, null, null, null, null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void list_includesPenalty_whenNoActiveRegularizationExists() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findAll((org.springframework.data.jpa.domain.Specification<AttendancePenalty>) any()))
                .thenReturn(List.of(p));

        List<AttendancePenaltyResponse> result = service.list(managerEmail, from, to, null, null, null, null, null);

        assertEquals(1, result.size());
        assertEquals(empId, result.get(0).getEmployeeUserId());
        assertTrue(result.get(0).isCancellable());
    }

    @Test
    void list_rejectedRegularization_doesNotBlockThePenalty() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findAll((org.springframework.data.jpa.domain.Specification<AttendancePenalty>) any()))
                .thenReturn(List.of(p));
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(List.of(empId), from, to))
                .thenReturn(List.of(RegularizationRequest.builder()
                        .employeeUserId(empId).attendanceDate(incidentDate).status("REJECTED").reason("x").build()));

        List<AttendancePenaltyResponse> result = service.list(managerEmail, from, to, null, null, null, null, null);

        assertEquals(1, result.size());
    }

    @Test
    void cancelBulk_partialSuccess_oneInvalidStatusDoesNotBlockTheOther() {
        AttendancePenalty cancellable = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        AttendancePenalty alreadyCancelled = penalty(AttendancePenaltyStatus.CANCELLED);
        when(attendancePenaltyRepository.findById(cancellable.getId())).thenReturn(Optional.of(cancellable));
        when(attendancePenaltyRepository.findById(alreadyCancelled.getId())).thenReturn(Optional.of(alreadyCancelled));

        PenaltyCancelResultResponse result = service.cancelBulk(
                managerEmail, List.of(cancellable.getId(), alreadyCancelled.getId()), "policy waived");

        assertEquals(List.of(cancellable.getId()), result.getSucceededIds());
        assertEquals(1, result.getFailed().size());
        assertEquals(alreadyCancelled.getId(), result.getFailed().get(0).getId());
        assertEquals(AttendancePenaltyStatus.CANCELLED, cancellable.getStatus());
        assertEquals("policy waived", cancellable.getCancellationReason());
        assertEquals(managerId, cancellable.getCancelledBy());
    }

    @Test
    void cancelBulk_employeeNoLongerADirectReport_fails() {
        AttendancePenalty penaltyForOther = AttendancePenalty.builder().id(UUID.randomUUID()).employeeUserId(otherEmpId)
                .incidentDate(incidentDate).discrepancyType("LATE_ARRIVAL").status(AttendancePenaltyStatus.PENDING_REVIEW)
                .evaluatedAt(LocalDateTime.now()).penalizedOn(LocalDateTime.now()).build();
        when(attendancePenaltyRepository.findById(penaltyForOther.getId())).thenReturn(Optional.of(penaltyForOther));

        PenaltyCancelResultResponse result = service.cancelBulk(managerEmail, List.of(penaltyForOther.getId()), "reason");

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
    }

    @Test
    void cancelBulk_activeRegularizationExists_blocksCancellation() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(
                List.of(empId), incidentDate, incidentDate))
                .thenReturn(List.of(RegularizationRequest.builder()
                        .employeeUserId(empId).attendanceDate(incidentDate).status("APPROVED").reason("x").build()));

        PenaltyCancelResultResponse result = service.cancelBulk(managerEmail, List.of(p.getId()), "reason");

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
        assertEquals(AttendancePenaltyStatus.PENDING_REVIEW, p.getStatus()); // unchanged
    }

    @Test
    void cancelBulk_penaltyNotFound_fails() {
        UUID missingId = UUID.randomUUID();
        when(attendancePenaltyRepository.findById(missingId)).thenReturn(Optional.empty());

        PenaltyCancelResultResponse result = service.cancelBulk(managerEmail, List.of(missingId), "reason");

        assertTrue(result.getSucceededIds().isEmpty());
        assertEquals(1, result.getFailed().size());
    }

    // ── Section 17: leave balance restoration on cancel/reverse ─────────────────────────────

    private AttendancePenalty paidLeavePenalty(String status) {
        AttendancePenalty p = penalty(status);
        p.setLeaveDeductionDays(new BigDecimal("1.5"));
        p.setLeaveBreakdown("{\"SICK\":1,\"CASUAL\":0.5}");
        return p;
    }

    @Test
    void cancelOne_paidLeavePenalty_restoresExactlyWhatWasDeducted_perLeaveType() {
        AttendancePenalty p = paidLeavePenalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findById(p.getId())).thenReturn(Optional.of(p));
        UUID sickTypeId = UUID.randomUUID();
        UUID casualTypeId = UUID.randomUUID();
        when(leaveTypeRepository.findByCode("SICK")).thenReturn(Optional.of(LeaveType.builder().id(sickTypeId).code("SICK").build()));
        when(leaveTypeRepository.findByCode("CASUAL")).thenReturn(Optional.of(LeaveType.builder().id(casualTypeId).code("CASUAL").build()));
        com.nforce.onehr.entity.LeaveBalance sickBalance = com.nforce.onehr.entity.LeaveBalance.builder()
                .employeeUserId(empId).year(incidentDate.getYear()).totalDays(new BigDecimal("10")).usedDays(new BigDecimal("3")).build();
        com.nforce.onehr.entity.LeaveBalance casualBalance = com.nforce.onehr.entity.LeaveBalance.builder()
                .employeeUserId(empId).year(incidentDate.getYear()).totalDays(new BigDecimal("5")).usedDays(new BigDecimal("2")).build();
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(empId, sickTypeId, incidentDate.getYear()))
                .thenReturn(Optional.of(sickBalance));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(empId, casualTypeId, incidentDate.getYear()))
                .thenReturn(Optional.of(casualBalance));

        PenaltyCancelResultResponse result = service.cancelBulk(managerEmail, List.of(p.getId()), "waived");

        assertEquals(List.of(p.getId()), result.getSucceededIds());
        assertEquals(new BigDecimal("2"), sickBalance.getUsedDays(), "3 - 1 (exactly what was recorded as deducted)");
        assertEquals(new BigDecimal("1.5"), casualBalance.getUsedDays(), "2 - 0.5");
        verify(notificationService).send(eq(empId), eq("ATTENDANCE_PENALTY_CANCELLED"), any(), any(), any());
        // Gap-038: the audit trail must record exactly which leave types/amounts were restored,
        // not just the before/after status.
        org.mockito.ArgumentCaptor<String> afterCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(managerId), eq("ATTENDANCE_PENALTY_CANCELLED"), eq(p.getId()), any(), afterCaptor.capture());
        assertTrue(afterCaptor.getValue().contains("\"leaveRestored\""));
        assertTrue(afterCaptor.getValue().contains("\"SICK\":1"));
        assertTrue(afterCaptor.getValue().contains("\"CASUAL\":0.5"));
    }

    @Test
    void cancelOne_lossOfPayPenalty_neverTouchesLeaveBalance() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW); // no leaveBreakdown
        when(attendancePenaltyRepository.findById(p.getId())).thenReturn(Optional.of(p));

        service.cancelBulk(managerEmail, List.of(p.getId()), "waived");

        verifyNoInteractions(leaveBalanceRepository);
    }

    @Test
    void cancelOne_neverRestoresBelowZero_defensiveFloor() {
        AttendancePenalty p = paidLeavePenalty(AttendancePenaltyStatus.PENDING_REVIEW);
        p.setLeaveBreakdown("{\"SICK\":5}");
        when(attendancePenaltyRepository.findById(p.getId())).thenReturn(Optional.of(p));
        UUID sickTypeId = UUID.randomUUID();
        when(leaveTypeRepository.findByCode("SICK")).thenReturn(Optional.of(LeaveType.builder().id(sickTypeId).code("SICK").build()));
        com.nforce.onehr.entity.LeaveBalance sickBalance = com.nforce.onehr.entity.LeaveBalance.builder()
                .employeeUserId(empId).year(incidentDate.getYear()).totalDays(new BigDecimal("10")).usedDays(new BigDecimal("2")).build();
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(empId, sickTypeId, incidentDate.getYear()))
                .thenReturn(Optional.of(sickBalance));

        service.cancelBulk(managerEmail, List.of(p.getId()), "waived");

        assertEquals(BigDecimal.ZERO, sickBalance.getUsedDays());
    }

    // ── Section 16/Gap-033/034: automatic reversal triggered per-penalty by the shared ────────
    // ── re-evaluation engine (ExceptionService#reevaluateAndReverseIfInvalid) ────────────────

    @Test
    void reverseIfActive_pendingReviewPenalty_reversesIt() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findById(p.getId())).thenReturn(Optional.of(p));

        service.reverseIfActive(p.getId(), managerId, "Attendance corrected via approved regularization", "ATTENDANCE_PENALTY_REVERSED");

        assertEquals(AttendancePenaltyStatus.REVERSED, p.getStatus());
        verify(attendancePenaltyRepository).transitionStatus(eq(p.getId()), eq(AttendancePenaltyStatus.REVERSED), eq(managerId), any(),
                eq("Attendance corrected via approved regularization"), any());
        // Gap-038: the audit afterState is now a JSON snapshot (status + reason + any leave/LOP
        // restoration detail), not the bare status string.
        org.mockito.ArgumentCaptor<String> afterCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(managerId), eq("ATTENDANCE_PENALTY_REVERSED"), eq(p.getId()),
                eq(AttendancePenaltyStatus.PENDING_REVIEW), afterCaptor.capture());
        assertTrue(afterCaptor.getValue().contains("\"status\":\"REVERSED\""));
        assertTrue(afterCaptor.getValue().contains("Attendance corrected via approved regularization"));
    }

    @Test
    void reverseIfActive_alreadyTerminalPenalty_isANoOp() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.CANCELLED);
        when(attendancePenaltyRepository.findById(p.getId())).thenReturn(Optional.of(p));

        service.reverseIfActive(p.getId(), managerId, "reason", "ATTENDANCE_PENALTY_REVERSED");

        assertEquals(AttendancePenaltyStatus.CANCELLED, p.getStatus(), "already-terminal penalty is left untouched");
        verifyNoInteractions(auditService);
    }

    @Test
    void reverseIfActive_penaltyNotFound_isANoOp() {
        UUID missingId = UUID.randomUUID();
        when(attendancePenaltyRepository.findById(missingId)).thenReturn(Optional.empty());

        service.reverseIfActive(missingId, managerId, "reason", "ATTENDANCE_PENALTY_REVERSED");

        verifyNoInteractions(auditService);
    }

    // ── Concurrency: a lost race must skip all side effects, not partially apply them ────────

    @Test
    void cancelOne_concurrentTransitionAlreadyApplied_skipsLeaveBalanceRestorationAndNotification() {
        AttendancePenalty p = paidLeavePenalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findById(p.getId())).thenReturn(Optional.of(p));
        // Simulate another concurrent call having already transitioned this exact penalty —
        // the conditional UPDATE's WHERE clause no longer matches, so it affects zero rows.
        when(attendancePenaltyRepository.transitionStatus(eq(p.getId()), any(), any(), any(), any(), any())).thenReturn(0);

        PenaltyCancelResultResponse result = service.cancelBulk(managerEmail, List.of(p.getId()), "waived");

        assertEquals(List.of(p.getId()), result.getSucceededIds(),
                "losing the race is not an error from the caller's point of view — the penalty is already reversed");
        verifyNoInteractions(leaveBalanceRepository);
        verifyNoInteractions(notificationService);
        verifyNoInteractions(auditService);
    }

    @Test
    void reverseIfActive_concurrentTransitionAlreadyApplied_skipsSideEffectsSilently() {
        AttendancePenalty p = penalty(AttendancePenaltyStatus.PENDING_REVIEW);
        when(attendancePenaltyRepository.findById(p.getId())).thenReturn(Optional.of(p));
        // Simulate another concurrent call (a manual cancel, or another reversal) having already
        // transitioned this exact penalty — the conditional UPDATE's WHERE clause no longer
        // matches, so it affects zero rows.
        when(attendancePenaltyRepository.transitionStatus(eq(p.getId()), any(), any(), any(), any(), any())).thenReturn(0);

        service.reverseIfActive(p.getId(), managerId, "reason", "ATTENDANCE_PENALTY_REVERSED");

        assertEquals(AttendancePenaltyStatus.PENDING_REVIEW, p.getStatus(), "lost the race — left exactly as the winning call set it");
        verifyNoInteractions(auditService);
    }
}
