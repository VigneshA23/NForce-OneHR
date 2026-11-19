package com.nforce.onehr.service;

import com.nforce.onehr.dto.attendance.PenaltyCancelResultResponse;
import com.nforce.onehr.dto.payroll.*;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as the rest of this epic's service tests. */
@ExtendWith(MockitoExtension.class)
class PayrollRunReviewServiceTest {

    @Mock private PayrollRunReviewItemRepository payrollRunReviewItemRepository;
    @Mock private PayrollRunRepository payrollRunRepository;
    @Mock private AttendancePenaltyService attendancePenaltyService;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private PayrollRunReviewService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID runId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    private final LocalDate cycleStart = LocalDate.of(2026, 10, 26);
    private final LocalDate cycleEnd = LocalDate.of(2026, 11, 25);
    private final PayCycleWindow window = new PayCycleWindow(cycleStart, cycleEnd);

    @BeforeEach
    void setUp() {
        service = new PayrollRunReviewService(payrollRunReviewItemRepository, payrollRunRepository,
                attendancePenaltyService, employeeRepository, userRepository, auditService, auditSnapshot);

        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
        lenient().when(employeeRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(userRepository.findById(any())).thenReturn(Optional.empty());

        lenient().when(payrollRunReviewItemRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(payrollRunReviewItemRepository.save(any(PayrollRunReviewItem.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PayrollRun run() {
        return PayrollRun.builder().id(runId).status(PayrollRunStatus.DRAFT).build();
    }

    @Test
    void generateItemsForEmployee_createsLeaveAndPenaltyAndNoAttendanceItems() {
        LeaveType unpaid = LeaveType.builder().id(UUID.randomUUID()).name("Unpaid Leave").build();
        LeaveRequest leaveRequest = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(unpaid).startDate(LocalDate.of(2026, 10, 28)).endDate(LocalDate.of(2026, 10, 28))
                .totalDays(new BigDecimal("1")).status("APPROVED").build();

        AttendancePenalty penalty = AttendancePenalty.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .incidentDate(LocalDate.of(2026, 10, 30)).status(AttendancePenaltyStatus.PENDING_REVIEW)
                .lopDays(new BigDecimal("0.50")).build();

        LocalDate noAttendanceDate = LocalDate.of(2026, 11, 2);
        PayrollLopCalculationResponse lop = PayrollLopCalculationResponse.builder()
                .needsManualReviewDates(List.of(noAttendanceDate)).build();

        service.generateItemsForEmployee(run(), employeeId, window, lop, List.of(leaveRequest), List.of(penalty));

        // The ordering this story depends on: delete-then-recreate, never the other way round —
        // see this method's own Javadoc for the regenerate bug that ordering it backwards caused
        // (a MARK_LOP/REJECTED decision wiped before PayrollLopCalculationService ever read it).
        verify(payrollRunReviewItemRepository).deleteByPayrollRunIdAndEmployeeId(runId, employeeId);
        org.mockito.ArgumentCaptor<List<PayrollRunReviewItem>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(payrollRunReviewItemRepository).saveAll(captor.capture());
        List<PayrollRunReviewItem> items = captor.getValue();
        assertEquals(3, items.size());

        PayrollRunReviewItem leaveItem = items.stream().filter(i -> PayrollReviewItemSourceType.LEAVE_UNPAID.equals(i.getSourceType())).findFirst().orElseThrow();
        assertEquals(PayrollRunReviewStepType.LEAVE_APPLIED, leaveItem.getStepType());
        assertEquals(leaveRequest.getId(), leaveItem.getSourceId());
        assertEquals(0, new BigDecimal("1").compareTo(leaveItem.getDays()));
        assertEquals("Unpaid Leave", leaveItem.getLeaveTypeName());
        assertEquals(LeaveReviewDecision.PENDING, leaveItem.getDecision());

        PayrollRunReviewItem penaltyItem = items.stream().filter(i -> PayrollReviewItemSourceType.PENALTY.equals(i.getSourceType())).findFirst().orElseThrow();
        assertEquals(PayrollRunReviewStepType.LEAVE_APPLIED, penaltyItem.getStepType());
        assertEquals(penalty.getId(), penaltyItem.getSourceId());
        assertEquals(0, new BigDecimal("0.50").compareTo(penaltyItem.getDays()));

        PayrollRunReviewItem noAttendanceItem = items.stream().filter(i -> PayrollRunReviewStepType.NO_ATTENDANCE.equals(i.getStepType())).findFirst().orElseThrow();
        assertEquals(PayrollReviewItemSourceType.NO_ATTENDANCE, noAttendanceItem.getSourceType());
        assertNull(noAttendanceItem.getSourceId());
        assertEquals(noAttendanceDate, noAttendanceItem.getItemDate());
        assertEquals(0, BigDecimal.ONE.compareTo(noAttendanceItem.getDays()));
        assertEquals(NoAttendanceReviewDecision.PENDING, noAttendanceItem.getDecision());
    }

    @Test
    void generateItemsForEmployee_nullNeedsManualReviewDates_doesNotThrow() {
        PayrollLopCalculationResponse lop = PayrollLopCalculationResponse.builder().build();

        assertDoesNotThrow(() -> service.generateItemsForEmployee(run(), employeeId, window, lop, List.of(), List.of()));
    }

    @Test
    void deleteItemsForEmployee_delegatesToRepository() {
        service.deleteItemsForEmployee(runId, employeeId);

        verify(payrollRunReviewItemRepository).deleteByPayrollRunIdAndEmployeeId(runId, employeeId);
    }

    @Test
    void isStepComplete_noPendingItems_true() {
        when(payrollRunReviewItemRepository.existsByPayrollRunIdAndStepTypeAndDecision(
                runId, PayrollRunReviewStepType.LEAVE_APPLIED, LeaveReviewDecision.PENDING)).thenReturn(false);

        assertTrue(service.isStepComplete(runId, PayrollRunReviewStepType.LEAVE_APPLIED));
    }

    @Test
    void isStepComplete_pendingItemsExist_false() {
        when(payrollRunReviewItemRepository.existsByPayrollRunIdAndStepTypeAndDecision(
                runId, PayrollRunReviewStepType.NO_ATTENDANCE, NoAttendanceReviewDecision.PENDING)).thenReturn(true);

        assertFalse(service.isStepComplete(runId, PayrollRunReviewStepType.NO_ATTENDANCE));
    }

    @Test
    void bulkDecide_approve_isPureAcknowledgement() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        PayrollRunReviewItem item = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.LEAVE_APPLIED)
                .sourceType(PayrollReviewItemSourceType.LEAVE_UNPAID).sourceId(UUID.randomUUID())
                .itemDate(cycleStart).days(BigDecimal.ONE).decision(LeaveReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findByIdIn(List.of(item.getId()))).thenReturn(List.of(item));

        BulkDecideLeaveReviewRequest req = new BulkDecideLeaveReviewRequest();
        req.setItemIds(List.of(item.getId()));
        req.setDecision("APPROVED");

        BulkDecideLeaveReviewResponse resp = service.bulkDecideLeaveApplied(runId, req, actorEmail);

        assertEquals(List.of(item.getId()), resp.getSucceededIds());
        assertEquals(LeaveReviewDecision.APPROVED, item.getDecision());
        verifyNoInteractions(attendancePenaltyService);
        verify(auditService).log(eq(actorId), eq("PAYROLL_RUN_REVIEW_LEAVE_DECIDED"), any(), any(), any());
    }

    @Test
    void bulkDecide_rejectLeaveUnpaid_setsDecisionOnly() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        PayrollRunReviewItem item = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.LEAVE_APPLIED)
                .sourceType(PayrollReviewItemSourceType.LEAVE_UNPAID).sourceId(UUID.randomUUID())
                .itemDate(cycleStart).days(BigDecimal.ONE).decision(LeaveReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findByIdIn(List.of(item.getId()))).thenReturn(List.of(item));

        BulkDecideLeaveReviewRequest req = new BulkDecideLeaveReviewRequest();
        req.setItemIds(List.of(item.getId()));
        req.setDecision("REJECTED");
        req.setReason("Not a real unpaid day");

        BulkDecideLeaveReviewResponse resp = service.bulkDecideLeaveApplied(runId, req, actorEmail);

        assertEquals(List.of(item.getId()), resp.getSucceededIds());
        assertEquals(LeaveReviewDecision.REJECTED, item.getDecision());
        verifyNoInteractions(attendancePenaltyService);
    }

    @Test
    void bulkDecide_rejectPenalty_callsCancelBulk_onlySucceededOnesMarkedRejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        UUID penaltyId1 = UUID.randomUUID();
        UUID penaltyId2 = UUID.randomUUID();
        PayrollRunReviewItem item1 = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.LEAVE_APPLIED)
                .sourceType(PayrollReviewItemSourceType.PENALTY).sourceId(penaltyId1)
                .itemDate(cycleStart).days(BigDecimal.ONE).decision(LeaveReviewDecision.PENDING).build();
        PayrollRunReviewItem item2 = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.LEAVE_APPLIED)
                .sourceType(PayrollReviewItemSourceType.PENALTY).sourceId(penaltyId2)
                .itemDate(cycleStart).days(BigDecimal.ONE).decision(LeaveReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findByIdIn(List.of(item1.getId(), item2.getId()))).thenReturn(List.of(item1, item2));
        when(attendancePenaltyService.cancelBulk(eq(actorEmail), any(), eq("reason"))).thenReturn(
                PenaltyCancelResultResponse.builder()
                        .succeededIds(List.of(penaltyId1))
                        .failed(List.of(PenaltyCancelResultResponse.BulkFailureDto.builder()
                                .id(penaltyId2).reason("A pending regularization exists").build()))
                        .build());

        BulkDecideLeaveReviewRequest req = new BulkDecideLeaveReviewRequest();
        req.setItemIds(List.of(item1.getId(), item2.getId()));
        req.setDecision("REJECTED");
        req.setReason("reason");

        BulkDecideLeaveReviewResponse resp = service.bulkDecideLeaveApplied(runId, req, actorEmail);

        assertEquals(List.of(item1.getId()), resp.getSucceededIds());
        assertEquals(1, resp.getFailed().size());
        assertEquals(item2.getId(), resp.getFailed().get(0).getItemId());
        assertEquals(LeaveReviewDecision.REJECTED, item1.getDecision());
        assertEquals(LeaveReviewDecision.PENDING, item2.getDecision());
    }

    @Test
    void bulkDecide_itemNotOnRun_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        when(payrollRunReviewItemRepository.findByIdIn(any())).thenReturn(List.of());

        BulkDecideLeaveReviewRequest req = new BulkDecideLeaveReviewRequest();
        req.setItemIds(List.of(UUID.randomUUID()));
        req.setDecision("APPROVED");

        assertThrows(NoSuchElementException.class, () -> service.bulkDecideLeaveApplied(runId, req, actorEmail));
    }

    @Test
    void decideNoAttendance_markLop_succeeds() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        PayrollRunReviewItem item = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.NO_ATTENDANCE)
                .sourceType(PayrollReviewItemSourceType.NO_ATTENDANCE).itemDate(cycleStart).days(BigDecimal.ONE)
                .decision(NoAttendanceReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        DecideNoAttendanceReviewRequest req = new DecideNoAttendanceReviewRequest();
        req.setAction("MARK_LOP");

        PayrollRunReviewItemResponse resp = service.decideNoAttendance(runId, item.getId(), req, actorEmail);

        assertEquals("MARK_LOP", resp.getDecision());
        verify(auditService).log(eq(actorId), eq("PAYROLL_RUN_REVIEW_NO_ATTENDANCE_DECIDED"), eq(item.getId()), any(), any());
    }

    @Test
    void decideNoAttendance_deductFromLeave_requiresLeaveTypeId() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        PayrollRunReviewItem item = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.NO_ATTENDANCE)
                .sourceType(PayrollReviewItemSourceType.NO_ATTENDANCE).itemDate(cycleStart).days(BigDecimal.ONE)
                .decision(NoAttendanceReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        DecideNoAttendanceReviewRequest req = new DecideNoAttendanceReviewRequest();
        req.setAction("DEDUCT_FROM_LEAVE");

        assertThrows(IllegalArgumentException.class, () -> service.decideNoAttendance(runId, item.getId(), req, actorEmail));
    }

    @Test
    void decideNoAttendance_noAction_requiresComment() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        PayrollRunReviewItem item = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.NO_ATTENDANCE)
                .sourceType(PayrollReviewItemSourceType.NO_ATTENDANCE).itemDate(cycleStart).days(BigDecimal.ONE)
                .decision(NoAttendanceReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        DecideNoAttendanceReviewRequest req = new DecideNoAttendanceReviewRequest();
        req.setAction("NO_ACTION");

        assertThrows(IllegalArgumentException.class, () -> service.decideNoAttendance(runId, item.getId(), req, actorEmail));
    }

    @Test
    void decideNoAttendance_wrongStepType_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        PayrollRunReviewItem item = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.LEAVE_APPLIED)
                .sourceType(PayrollReviewItemSourceType.LEAVE_UNPAID).itemDate(cycleStart).days(BigDecimal.ONE)
                .decision(LeaveReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        DecideNoAttendanceReviewRequest req = new DecideNoAttendanceReviewRequest();
        req.setAction("MARK_LOP");

        assertThrows(IllegalArgumentException.class, () -> service.decideNoAttendance(runId, item.getId(), req, actorEmail));
    }

    @Test
    void decideNoAttendance_differentRun_rejected() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        PayrollRun otherRun = PayrollRun.builder().id(UUID.randomUUID()).status(PayrollRunStatus.DRAFT).build();
        PayrollRunReviewItem item = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(otherRun)
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.NO_ATTENDANCE)
                .sourceType(PayrollReviewItemSourceType.NO_ATTENDANCE).itemDate(cycleStart).days(BigDecimal.ONE)
                .decision(NoAttendanceReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        DecideNoAttendanceReviewRequest req = new DecideNoAttendanceReviewRequest();
        req.setAction("MARK_LOP");

        assertThrows(IllegalArgumentException.class, () -> service.decideNoAttendance(runId, item.getId(), req, actorEmail));
    }

    @Test
    void bulkDecideNoAttendance_markLop_decidesEveryItem() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        PayrollRunReviewItem item1 = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.NO_ATTENDANCE)
                .sourceType(PayrollReviewItemSourceType.NO_ATTENDANCE).itemDate(cycleStart).days(BigDecimal.ONE)
                .decision(NoAttendanceReviewDecision.PENDING).build();
        PayrollRunReviewItem item2 = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.NO_ATTENDANCE)
                .sourceType(PayrollReviewItemSourceType.NO_ATTENDANCE).itemDate(cycleStart.plusDays(1)).days(BigDecimal.ONE)
                .decision(NoAttendanceReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findByIdIn(List.of(item1.getId(), item2.getId()))).thenReturn(List.of(item1, item2));
        // bulkDecide is the real UPDATE ... WHERE id IN (...) — mutate the same mocked entities in
        // place so the service's post-update re-fetch (same findByIdIn stub) reflects it.
        doAnswer(inv -> {
            for (PayrollRunReviewItem item : List.of(item1, item2)) {
                item.setDecision(inv.getArgument(1));
                item.setSelectedLeaveTypeId(inv.getArgument(2));
                item.setComment(inv.getArgument(3));
                item.setDecidedBy(inv.getArgument(4));
                item.setDecidedAt(inv.getArgument(5));
            }
            return null;
        }).when(payrollRunReviewItemRepository).bulkDecide(eq(List.of(item1.getId(), item2.getId())), any(), any(), any(), any(), any());

        BulkDecideNoAttendanceRequest req = new BulkDecideNoAttendanceRequest();
        req.setItemIds(List.of(item1.getId(), item2.getId()));
        req.setAction("MARK_LOP");

        List<PayrollRunReviewItemResponse> result = service.bulkDecideNoAttendance(runId, req, actorEmail);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(r -> "MARK_LOP".equals(r.getDecision())));
        // One summary audit row for the whole batch, not one per item — see the method's own
        // Javadoc: a per-item REQUIRES_NEW audit call at real batch sizes (169 items observed)
        // blew the transaction timeout against Neon.
        verify(auditService, times(1)).log(eq(actorId), eq("PAYROLL_RUN_REVIEW_NO_ATTENDANCE_DECIDED"), eq(runId), any(), any());
    }

    @Test
    void bulkDecideNoAttendance_deductFromLeave_requiresLeaveTypeId() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        BulkDecideNoAttendanceRequest req = new BulkDecideNoAttendanceRequest();
        req.setItemIds(List.of(UUID.randomUUID()));
        req.setAction("DEDUCT_FROM_LEAVE");

        assertThrows(IllegalArgumentException.class, () -> service.bulkDecideNoAttendance(runId, req, actorEmail));
        verifyNoInteractions(payrollRunReviewItemRepository);
    }

    @Test
    void bulkDecideNoAttendance_noAction_requiresComment() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        BulkDecideNoAttendanceRequest req = new BulkDecideNoAttendanceRequest();
        req.setItemIds(List.of(UUID.randomUUID()));
        req.setAction("NO_ACTION");

        assertThrows(IllegalArgumentException.class, () -> service.bulkDecideNoAttendance(runId, req, actorEmail));
    }

    @Test
    void bulkDecideNoAttendance_itemFromDifferentRun_rejectsWholeBatch() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        PayrollRun otherRun = PayrollRun.builder().id(UUID.randomUUID()).status(PayrollRunStatus.DRAFT).build();
        PayrollRunReviewItem item = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(otherRun)
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.NO_ATTENDANCE)
                .sourceType(PayrollReviewItemSourceType.NO_ATTENDANCE).itemDate(cycleStart).days(BigDecimal.ONE)
                .decision(NoAttendanceReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findByIdIn(List.of(item.getId()))).thenReturn(List.of(item));

        BulkDecideNoAttendanceRequest req = new BulkDecideNoAttendanceRequest();
        req.setItemIds(List.of(item.getId()));
        req.setAction("MARK_LOP");

        assertThrows(NoSuchElementException.class, () -> service.bulkDecideNoAttendance(runId, req, actorEmail));
        verify(payrollRunReviewItemRepository, never()).save(any());
    }

    @Test
    void bulkDecideNoAttendance_wrongStepType_rejectsWholeBatch() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        PayrollRunReviewItem item = PayrollRunReviewItem.builder().id(UUID.randomUUID()).payrollRun(run())
                .employeeId(employeeId).stepType(PayrollRunReviewStepType.LEAVE_APPLIED)
                .sourceType(PayrollReviewItemSourceType.LEAVE_UNPAID).itemDate(cycleStart).days(BigDecimal.ONE)
                .decision(LeaveReviewDecision.PENDING).build();
        when(payrollRunReviewItemRepository.findByIdIn(List.of(item.getId()))).thenReturn(List.of(item));

        BulkDecideNoAttendanceRequest req = new BulkDecideNoAttendanceRequest();
        req.setItemIds(List.of(item.getId()));
        req.setAction("MARK_LOP");

        assertThrows(IllegalArgumentException.class, () -> service.bulkDecideNoAttendance(runId, req, actorEmail));
    }

    @Test
    void bulkDecideNoAttendance_missingItem_rejectsWholeBatch() {
        when(payrollRunRepository.findById(runId)).thenReturn(Optional.of(run()));
        BulkDecideNoAttendanceRequest req = new BulkDecideNoAttendanceRequest();
        req.setItemIds(List.of(UUID.randomUUID()));
        req.setAction("MARK_LOP");
        when(payrollRunReviewItemRepository.findByIdIn(req.getItemIds())).thenReturn(List.of());

        assertThrows(NoSuchElementException.class, () -> service.bulkDecideNoAttendance(runId, req, actorEmail));
    }
}
