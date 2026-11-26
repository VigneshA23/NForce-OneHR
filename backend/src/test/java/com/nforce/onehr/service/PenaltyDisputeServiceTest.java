package com.nforce.onehr.service;

import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.attendance.DecidePenaltyDisputeRequest;
import com.nforce.onehr.dto.attendance.ForwardPenaltyDisputeRequest;
import com.nforce.onehr.dto.attendance.PenaltyDisputeResponse;
import com.nforce.onehr.dto.attendance.RaisePenaltyDisputeRequest;
import com.nforce.onehr.entity.AttendancePenalty;
import com.nforce.onehr.entity.AttendancePenaltyDispute;
import com.nforce.onehr.entity.AttendancePenaltyStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.ExceptionType;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendancePenaltyDisputeRepository;
import com.nforce.onehr.repository.AttendancePenaltyRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.security.EffectivePermissionService;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PN-02: employee dispute of an attendance penalty - routing, eligibility and resolution. */
@ExtendWith(MockitoExtension.class)
class PenaltyDisputeServiceTest {

    @Mock private AttendancePenaltyDisputeRepository disputeRepository;
    @Mock private AttendancePenaltyRepository penaltyRepository;
    @Mock private AttendancePenaltyService attendancePenaltyService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository managerHistoryRepository;
    @Mock private EmployeeService employeeService;
    @Mock private PayslipRepository payslipRepository;
    @Mock private NotificationService notificationService;
    @Mock private AuditService auditService;
    @Mock private EffectivePermissionService effectivePermissionService;
    @InjectMocks private PenaltyDisputeService service;

    private final UUID empId = UUID.randomUUID();
    private final UUID mgrId = UUID.randomUUID();
    private final UUID hrId = UUID.randomUUID();
    private final UUID penaltyId = UUID.randomUUID();
    private final LocalDate incident = LocalDate.of(2026, 11, 10);
    private Employee employee;
    private Employee manager;
    private Employee hr;
    private AttendancePenalty penalty;

    @BeforeEach
    void setUp() {
        employee = Employee.builder().userId(empId).fullName("Emp One").employeeCode("NF-1").user(mock(User.class)).build();
        manager = Employee.builder().userId(mgrId).fullName("Mgr").user(mock(User.class)).build();
        hr = Employee.builder().userId(hrId).fullName("HR").user(mock(User.class)).build();
        lenient().when(employeeRepository.findByUser_Email("emp@x.com")).thenReturn(Optional.of(employee));
        lenient().when(employeeRepository.findByUser_Email("mgr@x.com")).thenReturn(Optional.of(manager));
        lenient().when(employeeRepository.findByUser_Email("hr@x.com")).thenReturn(Optional.of(hr));
        penalty = AttendancePenalty.builder().id(penaltyId).employeeUserId(empId).incidentDate(incident)
                .discrepancyType(ExceptionType.LATE_ARRIVAL).status(AttendancePenaltyStatus.PENDING_REVIEW)
                .deductionDays(new BigDecimal("0.5")).build();
        lenient().when(penaltyRepository.findById(penaltyId)).thenReturn(Optional.of(penalty));
        lenient().when(penaltyRepository.findAllById(any())).thenReturn(List.of(penalty));
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of(employee));
        lenient().when(disputeRepository.save(any())).thenAnswer(i -> {
            AttendancePenaltyDispute d = i.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });
    }

    private RaisePenaltyDisputeRequest raiseReq() {
        RaisePenaltyDisputeRequest r = new RaisePenaltyDisputeRequest();
        r.setReason("Biometric device was down");
        r.setEvidence("IT ticket 4821");
        return r;
    }

    private void managerExists(boolean yes) {
        EmployeeResponse.ManagerRef ref = yes ? mock(EmployeeResponse.ManagerRef.class) : null;
        if (yes) when(ref.getUserId()).thenReturn(mgrId.toString());
        when(employeeService.findCurrentManagersBulk(any())).thenReturn(yes ? Map.of(empId, ref) : Map.of());
    }

    private AttendancePenaltyDispute dispute(String status) {
        return AttendancePenaltyDispute.builder().id(UUID.randomUUID()).penaltyId(penaltyId).employeeUserId(empId)
                .reason("r").status(status).build();
    }

    // -- raise ---------------------------------------------------------------------------

    @Test
    void raiseRoutesToManagerFirst() {
        managerExists(true);
        PenaltyDisputeResponse res = service.raise("emp@x.com", penaltyId, raiseReq());
        assertEquals(AttendancePenaltyDispute.PENDING_MANAGER, res.getStatus());
        assertEquals("Late Arrival", res.getRule());
        verify(notificationService).send(eq(mgrId), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void raiseGoesStraightToHrWhenThereIsNoManager() {
        managerExists(false);
        assertEquals(AttendancePenaltyDispute.PENDING_HR, service.raise("emp@x.com", penaltyId, raiseReq()).getStatus());
    }

    @Test
    void raiseIsRefusedOnceThePenaltyIsNoLongerPendingReview() {
        penalty.setStatus(AttendancePenaltyStatus.CANCELLED);
        assertThrows(IllegalStateException.class, () -> service.raise("emp@x.com", penaltyId, raiseReq()));
    }

    @Test
    void raiseIsRefusedAfterPayrollLock() {
        when(payslipRepository.existsLockedPayslipCovering(empId, incident)).thenReturn(true);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.raise("emp@x.com", penaltyId, raiseReq()));
        assertTrue(e.getMessage().contains("locked"));
        verify(disputeRepository, never()).save(any());
    }

    @Test
    void raiseIsRefusedForSomeoneElsesPenaltyOrASecondDispute() {
        penalty.setEmployeeUserId(UUID.randomUUID());
        assertThrows(AccessDeniedException.class, () -> service.raise("emp@x.com", penaltyId, raiseReq()));
        penalty.setEmployeeUserId(empId);
        when(disputeRepository.findByPenaltyId(penaltyId)).thenReturn(Optional.of(dispute("UPHELD")));
        assertThrows(IllegalStateException.class, () -> service.raise("emp@x.com", penaltyId, raiseReq()));
    }

    @Test
    void disputableOnlyWhenActiveUndisputedAndUnlocked() {
        assertTrue(service.isDisputable(penalty, false));
        assertFalse(service.isDisputable(penalty, true));
        when(payslipRepository.existsLockedPayslipCovering(empId, incident)).thenReturn(true);
        assertFalse(service.isDisputable(penalty, false));
        penalty.setStatus(AttendancePenaltyStatus.REVERSED);
        assertFalse(service.isDisputable(penalty, false));
    }

    // -- manager step --------------------------------------------------------------------

    @Test
    void managerForwardsToHrWithRecommendation() {
        AttendancePenaltyDispute d = dispute(AttendancePenaltyDispute.PENDING_MANAGER);
        when(disputeRepository.findById(d.getId())).thenReturn(Optional.of(d));
        when(managerHistoryRepository.findCurrentDirectReportIds(mgrId)).thenReturn(List.of(empId));
        ForwardPenaltyDisputeRequest req = new ForwardPenaltyDisputeRequest();
        req.setRecommendation("REVERSE");
        req.setComment("Device outage confirmed");

        PenaltyDisputeResponse res = service.forward("mgr@x.com", d.getId(), req);

        assertEquals(AttendancePenaltyDispute.PENDING_HR, res.getStatus());
        assertEquals("REVERSE", res.getManagerRecommendation());
    }

    @Test
    void nonManagerCannotForward() {
        AttendancePenaltyDispute d = dispute(AttendancePenaltyDispute.PENDING_MANAGER);
        when(disputeRepository.findById(d.getId())).thenReturn(Optional.of(d));
        when(managerHistoryRepository.findCurrentDirectReportIds(mgrId)).thenReturn(List.of());
        ForwardPenaltyDisputeRequest req = new ForwardPenaltyDisputeRequest();
        req.setRecommendation("UPHOLD");
        req.setComment("x");
        assertThrows(AccessDeniedException.class, () -> service.forward("mgr@x.com", d.getId(), req));
    }

    // -- HR step -------------------------------------------------------------------------

    private DecidePenaltyDisputeRequest decision(String d) {
        DecidePenaltyDisputeRequest r = new DecidePenaltyDisputeRequest();
        r.setDecision(d);
        r.setComment("Reviewed");
        return r;
    }

    @Test
    void hrReversalUsesTheExistingReverseLogic() {
        when(effectivePermissionService.hasAuthority(any(), eq("attendance:EDIT:ALL"))).thenReturn(true);
        AttendancePenaltyDispute d = dispute(AttendancePenaltyDispute.PENDING_HR);
        when(disputeRepository.findById(d.getId())).thenReturn(Optional.of(d));

        PenaltyDisputeResponse res = service.decide("hr@x.com", d.getId(), decision("REVERSED"));

        assertEquals(AttendancePenaltyDispute.REVERSED, res.getStatus());
        verify(attendancePenaltyService).reverseIfActive(eq(penaltyId), eq(hrId), anyString(), eq("ATTENDANCE_PENALTY_DISPUTE_REVERSED"));
    }

    @Test
    void hrUpholdLeavesThePenaltyAlone() {
        when(effectivePermissionService.hasAuthority(any(), eq("attendance:EDIT:ALL"))).thenReturn(true);
        AttendancePenaltyDispute d = dispute(AttendancePenaltyDispute.PENDING_HR);
        when(disputeRepository.findById(d.getId())).thenReturn(Optional.of(d));

        PenaltyDisputeResponse res = service.decide("hr@x.com", d.getId(), decision("UPHELD"));

        assertEquals(AttendancePenaltyDispute.UPHELD, res.getStatus());
        verify(attendancePenaltyService, never()).reverseIfActive(any(), any(), any(), any());
        verify(notificationService).send(eq(empId), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void hrCannotReverseAfterPayrollLockOrDecideBeforeTheManagerStep() {
        when(effectivePermissionService.hasAuthority(any(), eq("attendance:EDIT:ALL"))).thenReturn(true);
        AttendancePenaltyDispute d = dispute(AttendancePenaltyDispute.PENDING_HR);
        when(disputeRepository.findById(d.getId())).thenReturn(Optional.of(d));
        when(payslipRepository.existsLockedPayslipCovering(empId, incident)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.decide("hr@x.com", d.getId(), decision("REVERSED")));
        verify(attendancePenaltyService, never()).reverseIfActive(any(), any(), any(), any());

        AttendancePenaltyDispute early = dispute(AttendancePenaltyDispute.PENDING_MANAGER);
        when(disputeRepository.findById(early.getId())).thenReturn(Optional.of(early));
        assertThrows(IllegalStateException.class, () -> service.decide("hr@x.com", early.getId(), decision("UPHELD")));
    }

    @Test
    void decisionNeedsHrAuthorityAndNeverOnYourOwnDispute() {
        AttendancePenaltyDispute d = dispute(AttendancePenaltyDispute.PENDING_HR);
        assertThrows(AccessDeniedException.class, () -> service.decide("mgr@x.com", d.getId(), decision("UPHELD")));

        when(effectivePermissionService.hasAuthority(any(), eq("attendance:EDIT:ALL"))).thenReturn(true);
        when(disputeRepository.findById(d.getId())).thenReturn(Optional.of(d));
        assertThrows(AccessDeniedException.class, () -> service.decide("emp@x.com", d.getId(), decision("UPHELD")));
    }

    @Test
    void queueShowsManagerItemsForReportsAndHrItemsForHr() {
        when(effectivePermissionService.hasAuthority(any(), eq("attendance:EDIT:TEAM"))).thenReturn(true);
        when(effectivePermissionService.hasAuthority(any(), eq("attendance:EDIT:ALL"))).thenReturn(true);
        when(managerHistoryRepository.findCurrentDirectReportIds(hrId)).thenReturn(List.of(empId));
        when(disputeRepository.findByStatusAndEmployeeUserIdInOrderByCreatedAtAsc(eq("PENDING_MANAGER"), any()))
                .thenReturn(List.of(dispute("PENDING_MANAGER")));
        when(disputeRepository.findByStatusOrderByCreatedAtAsc("PENDING_HR")).thenReturn(List.of(dispute("PENDING_HR")));

        assertEquals(2, service.queue("hr@x.com").size());
    }

    @Test
    void queueHidesPendingDisputesWhosePenaltyWasCancelledElsewhere() {
        when(effectivePermissionService.hasAuthority(any(), eq("attendance:EDIT:TEAM"))).thenReturn(false);
        when(effectivePermissionService.hasAuthority(any(), eq("attendance:EDIT:ALL"))).thenReturn(true);
        penalty.setStatus(AttendancePenaltyStatus.CANCELLED);
        when(disputeRepository.findByStatusOrderByCreatedAtAsc("PENDING_HR")).thenReturn(List.of(dispute("PENDING_HR")));
        assertTrue(service.queue("hr@x.com").isEmpty());
    }
}
