package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.attendance.WorkingDaySchedule;
import com.nforce.onehr.dto.payroll.OverrideLopRequest;
import com.nforce.onehr.dto.payroll.PayrollLopCalculationResponse;
import com.nforce.onehr.dto.payroll.RecomputeLopRequest;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit tests — same approach as other payroll service tests this epic. */
@ExtendWith(MockitoExtension.class)
class PayrollLopCalculationServiceTest {

    @Mock private PayrollLopCalculationRepository payrollLopCalculationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PayGroupRepository payGroupRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private WorkingDayService workingDayService;
    @Mock private LeaveBalanceAvailabilityService leaveBalanceAvailabilityService;
    @Mock private PayrollRunReviewItemRepository payrollRunReviewItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;

    private ObjectMapper objectMapper;
    private PayrollLopCalculationService service;

    private final UUID actorId = UUID.randomUUID();
    private final String actorEmail = "hr.admin@test.com";
    private final UUID employeeId = UUID.randomUUID();
    private final UUID payGroupId = UUID.randomUUID();

    // Same-month 28-day cycle (1st-28th), referenceDate well inside it — simplest possible window.
    private final LocalDate referenceDate = LocalDate.of(2026, 6, 15);
    private final LocalDate cycleStart = LocalDate.of(2026, 6, 1);
    private final LocalDate cycleEnd = LocalDate.of(2026, 6, 28);

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new PayrollLopCalculationService(payrollLopCalculationRepository, employeeRepository,
                payGroupRepository, attendanceRepository, attendancePenaltyRepository, leaveRequestRepository,
                leaveTypeRepository, workingDayService, leaveBalanceAvailabilityService, payrollRunReviewItemRepository,
                userRepository, objectMapper, auditService, auditSnapshot);

        User actor = User.builder().id(actorId).email(actorEmail).build();
        lenient().when(userRepository.findByEmail(actorEmail)).thenReturn(Optional.of(actor));
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");

        Employee employee = Employee.builder().userId(employeeId).build();
        lenient().when(employeeRepository.findById(employeeId)).thenReturn(Optional.of(employee));

        PayGroup payGroup = PayGroup.builder().id(payGroupId).cycleStartDay(1).cycleEndDay(28).build();
        lenient().when(payGroupRepository.findById(payGroupId)).thenReturn(Optional.of(payGroup));

        // Defaults for a "nothing happened" cycle — individual tests override what they need.
        lenient().when(workingDayService.computeExpectedWorkingDays(any(), eq(cycleStart), eq(cycleEnd)))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of()).build());
        lenient().when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetweenAndStatus(
                eq(employeeId), eq(cycleStart), eq(cycleEnd), eq(AttendancePenaltyStatus.PENDING_REVIEW)))
                .thenReturn(List.of());
        lenient().when(leaveTypeRepository.findAll()).thenReturn(List.of(
                leaveType("PAID"), leaveType("UNPAID")));
        lenient().when(leaveRequestRepository.findByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
                any(), any(), any(), any(), any())).thenReturn(List.of());
        lenient().when(leaveBalanceAvailabilityService.availablePaidDays(eq(employeeId), any(Integer.class))).thenReturn(BigDecimal.ZERO);
        lenient().when(payrollRunReviewItemRepository.findRejectedLeaveRequestIds(any(), any())).thenReturn(Set.of());
        lenient().when(payrollRunReviewItemRepository.countMarkLopDecisions(any(), any())).thenReturn(0L);
        lenient().when(payrollLopCalculationRepository.save(any(PayrollLopCalculation.class))).thenAnswer(inv -> {
            PayrollLopCalculation c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
    }

    private LeaveType leaveType(String classification) {
        return LeaveType.builder().id(UUID.randomUUID()).code(classification).classification(classification).build();
    }

    private RecomputeLopRequest recomputeReq() {
        RecomputeLopRequest req = new RecomputeLopRequest();
        req.setEmployeeId(employeeId);
        req.setPayGroupId(payGroupId);
        req.setReferenceDate(referenceDate);
        return req;
    }

    @Test
    void recompute_noInputData_computesZero() {
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());

        PayrollLopCalculationResponse resp = service.recompute(recomputeReq(), actorEmail);

        assertEquals(cycleStart, resp.getCycleStart());
        assertEquals(cycleEnd, resp.getCycleEnd());
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getComputedLopDays()));
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getEffectiveLopDays()));
        assertTrue(resp.getNeedsManualReviewDates().isEmpty());
        verify(auditService).log(eq(actorId), eq("PAYROLL_LOP_COMPUTED"), any(), isNull(), any());
    }

    @Test
    void recompute_unpaidLeave_countsFully() {
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        LeaveRequest request = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(leaveType("UNPAID")).startDate(cycleStart).endDate(cycleStart).totalDays(new BigDecimal("2"))
                .status("APPROVED").build();
        when(leaveRequestRepository.findByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
                eq(employeeId), any(), eq("APPROVED"), eq(cycleStart), eq(cycleEnd)))
                .thenReturn(List.of(request));

        PayrollLopCalculationResponse resp = service.recompute(recomputeReq(), actorEmail);

        assertEquals(0, new BigDecimal("2").compareTo(resp.getUnpaidLeaveDays()));
        assertEquals(0, new BigDecimal("2").compareTo(resp.getComputedLopDays()));
    }

    @Test
    void recompute_rejectedLeaveReviewItem_excludedFromSum() {
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        LeaveRequest request = LeaveRequest.builder().id(requestId).employeeUserId(employeeId)
                .leaveType(leaveType("UNPAID")).startDate(cycleStart).endDate(cycleStart).totalDays(new BigDecimal("2"))
                .status("APPROVED").build();
        when(leaveRequestRepository.findByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
                eq(employeeId), any(), eq("APPROVED"), eq(cycleStart), eq(cycleEnd)))
                .thenReturn(List.of(request));
        when(payrollRunReviewItemRepository.findRejectedLeaveRequestIds(runId, employeeId)).thenReturn(Set.of(requestId));

        RecomputeLopRequest req = recomputeReq();
        req.setRunId(runId);
        PayrollLopCalculationResponse resp = service.recompute(req, actorEmail);

        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getUnpaidLeaveDays()));
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getComputedLopDays()));
    }

    @Test
    void recompute_markLopNoAttendanceDecision_addsExtraDay() {
        LocalDate missingDate = LocalDate.of(2026, 6, 5);
        UUID runId = UUID.randomUUID();
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        when(workingDayService.computeExpectedWorkingDays(any(), eq(cycleStart), eq(cycleEnd)))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(missingDate)).build());
        when(attendanceRepository.findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(employeeId, cycleStart, cycleEnd)).thenReturn(List.of());
        when(leaveBalanceAvailabilityService.availablePaidDays(employeeId, 2026)).thenReturn(new BigDecimal("5"));
        when(payrollRunReviewItemRepository.countMarkLopDecisions(runId, employeeId)).thenReturn(1L);

        RecomputeLopRequest req = recomputeReq();
        req.setRunId(runId);
        PayrollLopCalculationResponse resp = service.recompute(req, actorEmail);

        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getAutoLopNoAttendanceDays()));
        assertEquals(0, new BigDecimal("1").compareTo(resp.getComputedLopDays()));
        assertEquals(List.of(missingDate), resp.getNeedsManualReviewDates());
    }

    @Test
    void recompute_penaltyLopDays_countsFully() {
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        AttendancePenalty penalty = AttendancePenalty.builder()
                .employeeUserId(employeeId).incidentDate(LocalDate.of(2026, 6, 10))
                .status(AttendancePenaltyStatus.PENDING_REVIEW).lopDays(new BigDecimal("0.50")).build();
        when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetweenAndStatus(
                employeeId, cycleStart, cycleEnd, AttendancePenaltyStatus.PENDING_REVIEW))
                .thenReturn(List.of(penalty));

        PayrollLopCalculationResponse resp = service.recompute(recomputeReq(), actorEmail);

        assertEquals(0, new BigDecimal("0.50").compareTo(resp.getPenaltyLopDays()));
        assertEquals(0, new BigDecimal("0.50").compareTo(resp.getComputedLopDays()));
    }

    @Test
    void recompute_noAttendanceWithZeroPaidBalance_autoCountsAsLop() {
        LocalDate missingDate = LocalDate.of(2026, 6, 5);
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        when(workingDayService.computeExpectedWorkingDays(any(), eq(cycleStart), eq(cycleEnd)))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(missingDate)).build());
        when(attendanceRepository.findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(employeeId, cycleStart, cycleEnd)).thenReturn(List.of());
        when(leaveBalanceAvailabilityService.availablePaidDays(employeeId, 2026)).thenReturn(BigDecimal.ZERO);

        PayrollLopCalculationResponse resp = service.recompute(recomputeReq(), actorEmail);

        assertEquals(0, new BigDecimal("1").compareTo(resp.getAutoLopNoAttendanceDays()));
        assertEquals(0, new BigDecimal("1").compareTo(resp.getComputedLopDays()));
        assertTrue(resp.getNeedsManualReviewDates().isEmpty());
    }

    @Test
    void recompute_noAttendanceWithAvailablePaidBalance_goesToManualReview_notCounted() {
        LocalDate missingDate = LocalDate.of(2026, 6, 5);
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        when(workingDayService.computeExpectedWorkingDays(any(), eq(cycleStart), eq(cycleEnd)))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(missingDate)).build());
        when(attendanceRepository.findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(employeeId, cycleStart, cycleEnd)).thenReturn(List.of());
        when(leaveBalanceAvailabilityService.availablePaidDays(employeeId, 2026)).thenReturn(new BigDecimal("5"));

        PayrollLopCalculationResponse resp = service.recompute(recomputeReq(), actorEmail);

        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getAutoLopNoAttendanceDays()));
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getComputedLopDays()));
        assertEquals(List.of(missingDate), resp.getNeedsManualReviewDates());
    }

    @Test
    void recompute_attendedDate_excludedFromNoAttendanceCount() {
        LocalDate attendedDate = LocalDate.of(2026, 6, 5);
        LocalDate missingDate = LocalDate.of(2026, 6, 6);
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        when(workingDayService.computeExpectedWorkingDays(any(), eq(cycleStart), eq(cycleEnd)))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(attendedDate, missingDate)).build());
        Attendance attendance = Attendance.builder().employeeUserId(employeeId).workDate(attendedDate).build();
        when(attendanceRepository.findByEmployeeUserIdAndWorkDateBetweenOrderByWorkDateDesc(employeeId, cycleStart, cycleEnd))
                .thenReturn(List.of(attendance));
        when(leaveBalanceAvailabilityService.availablePaidDays(employeeId, 2026)).thenReturn(new BigDecimal("5"));

        PayrollLopCalculationResponse resp = service.recompute(recomputeReq(), actorEmail);

        assertEquals(List.of(missingDate), resp.getNeedsManualReviewDates());
    }

    @Test
    void recompute_penalizedDate_excludedFromNoAttendanceCount() {
        LocalDate penalizedDate = LocalDate.of(2026, 6, 5);
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());
        when(workingDayService.computeExpectedWorkingDays(any(), eq(cycleStart), eq(cycleEnd)))
                .thenReturn(WorkingDaySchedule.builder().employeeUserId(employeeId).workingDates(Set.of(penalizedDate)).build());
        AttendancePenalty penalty = AttendancePenalty.builder()
                .employeeUserId(employeeId).incidentDate(penalizedDate)
                .status(AttendancePenaltyStatus.PENDING_REVIEW).lopDays(new BigDecimal("1")).build();
        when(attendancePenaltyRepository.findByEmployeeUserIdAndIncidentDateBetweenAndStatus(
                employeeId, cycleStart, cycleEnd, AttendancePenaltyStatus.PENDING_REVIEW))
                .thenReturn(List.of(penalty));

        PayrollLopCalculationResponse resp = service.recompute(recomputeReq(), actorEmail);

        // Only counted once (via penaltyLopDays), not double-counted as a separate no-attendance day.
        assertEquals(0, new BigDecimal("1").compareTo(resp.getComputedLopDays()));
    }

    @Test
    void recompute_existingRow_updatesComputedFields_preservesOverride() {
        PayrollLopCalculation existing = PayrollLopCalculation.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).cycleStart(cycleStart).cycleEnd(cycleEnd)
                .unpaidLeaveDays(BigDecimal.ZERO).penaltyLopDays(BigDecimal.ZERO).autoLopNoAttendanceDays(BigDecimal.ZERO)
                .computedLopDays(BigDecimal.ZERO).needsManualReviewDates("[]").computedAt(LocalDateTime.now().minusDays(1))
                .overrideLopDays(new BigDecimal("3")).overrideReason("earlier manual fix")
                .overriddenBy(UUID.randomUUID()).overriddenAt(LocalDateTime.now().minusDays(1))
                .build();
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.of(existing));
        LeaveRequest request = LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(employeeId)
                .leaveType(leaveType("UNPAID")).startDate(cycleStart).endDate(cycleStart).totalDays(new BigDecimal("1"))
                .status("APPROVED").build();
        when(leaveRequestRepository.findByEmployeeUserIdAndLeaveTypeIdInAndStatusAndStartDateBetween(
                eq(employeeId), any(), eq("APPROVED"), eq(cycleStart), eq(cycleEnd)))
                .thenReturn(List.of(request));

        PayrollLopCalculationResponse resp = service.recompute(recomputeReq(), actorEmail);

        assertEquals(0, new BigDecimal("1").compareTo(resp.getComputedLopDays()));
        assertEquals(0, new BigDecimal("3").compareTo(resp.getOverrideLopDays()));
        assertEquals(0, new BigDecimal("3").compareTo(resp.getEffectiveLopDays()));
        assertEquals("earlier manual fix", resp.getOverrideReason());
        verify(auditService).log(eq(actorId), eq("PAYROLL_LOP_COMPUTED"), any(), eq("{}"), any());
    }

    @Test
    void override_withoutExistingCalculation_throwsIllegalState() {
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());

        OverrideLopRequest req = new OverrideLopRequest();
        req.setEmployeeId(employeeId);
        req.setPayGroupId(payGroupId);
        req.setReferenceDate(referenceDate);
        req.setOverrideLopDays(new BigDecimal("2"));
        req.setOverrideReason("correction");

        assertThrows(IllegalStateException.class, () -> service.override(req, actorEmail));
    }

    @Test
    void override_setsFieldsAndAudits_neverTouchesComputedLopDays() {
        PayrollLopCalculation existing = PayrollLopCalculation.builder()
                .id(UUID.randomUUID()).employeeId(employeeId).cycleStart(cycleStart).cycleEnd(cycleEnd)
                .unpaidLeaveDays(BigDecimal.ZERO).penaltyLopDays(BigDecimal.ZERO).autoLopNoAttendanceDays(new BigDecimal("4"))
                .computedLopDays(new BigDecimal("4")).needsManualReviewDates("[]").computedAt(LocalDateTime.now())
                .build();
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.of(existing));

        OverrideLopRequest req = new OverrideLopRequest();
        req.setEmployeeId(employeeId);
        req.setPayGroupId(payGroupId);
        req.setReferenceDate(referenceDate);
        req.setOverrideLopDays(BigDecimal.ZERO);
        req.setOverrideReason("intern should not be charged — see investigation");

        PayrollLopCalculationResponse resp = service.override(req, actorEmail);

        assertEquals(0, new BigDecimal("4").compareTo(resp.getComputedLopDays())); // untouched
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getOverrideLopDays()));
        assertEquals(0, BigDecimal.ZERO.compareTo(resp.getEffectiveLopDays()));
        assertEquals("intern should not be charged — see investigation", resp.getOverrideReason());
        assertEquals(actorId, resp.getOverriddenBy());
        verify(auditService).log(eq(actorId), eq("PAYROLL_LOP_OVERRIDDEN"), eq(existing.getId()), any(), any());
    }

    @Test
    void get_unknownCombination_throwsNoSuchElement() {
        when(payrollLopCalculationRepository.findByEmployeeIdAndCycleStartAndCycleEnd(employeeId, cycleStart, cycleEnd))
                .thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.get(employeeId, payGroupId, referenceDate));
    }
}
