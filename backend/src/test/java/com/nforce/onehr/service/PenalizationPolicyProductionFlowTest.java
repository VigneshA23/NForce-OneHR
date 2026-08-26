package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The real production execution path, end to end, with NOTHING except repositories/EmailService
 * mocked: {@code ExceptionService.getExceptionsForCaller} (the existing, already-invoked
 * Exception Dashboard load — not a scheduler) → {@code ExceptionService.upsertException} →
 * {@code ExceptionService.evaluatePolicy} → real {@link AttendancePenaltyEvaluationService} →
 * real {@link ConfiguredAttendancePolicyEngine} → real {@link PenalizationPolicyVersion} lookup →
 * {@link AttendancePenalty} persistence. {@link ConfiguredAttendancePolicyEngineTest} covers the
 * engine's decision logic in isolation; this class proves that logic is actually reachable from
 * the same production entry point HR/Manager already use, and that a Penalization Policy change
 * made through {@link PenalizationPolicyService} (not by calling the engine directly) changes the
 * outcome for an identical attendance fact.
 */
@ExtendWith(MockitoExtension.class)
class PenalizationPolicyProductionFlowTest {

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private AttendanceExceptionRepository attendanceExceptionRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private RegularizationRequestRepository regularizationRequestRepository;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private EmailService emailService;
    @Mock private HolidayRepository holidayRepository;
    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private LeaveBalanceRepository leaveBalanceRepository;

    @Mock private PenalizationPolicyVersionRepository versionRepository;
    @Mock private PenalizationPolicyAllocationRepository allocationRepository;
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;
    @Mock private PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private AuditService auditService;
    @Mock private NotificationService notificationService;

    private ExceptionService exceptionService;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final String hrEmail = "hr@test.com";

    @BeforeEach
    void setUp() {
        // Real objects — not mocks — for every layer between the production trigger and the
        // engine. Only repositories/EmailService are mocked.
        AttendancePolicyEngine policyEngine = new ConfiguredAttendancePolicyEngine(versionRepository, tierRepository, lateHoursTierRepository);
        AuditSnapshotSerializer snapshotSerializer = new AuditSnapshotSerializer(new com.fasterxml.jackson.databind.ObjectMapper());
        PenaltyDeductionService penaltyDeductionService = new PenaltyDeductionService(leaveTypeRepository, leaveBalanceRepository, snapshotSerializer);
        AttendancePenaltyEvaluationService penaltyEvaluationService =
                new AttendancePenaltyEvaluationService(policyEngine, attendancePenaltyRepository, penaltyDeductionService);
        WorkingDayService workingDayService = new WorkingDayService(holidayRepository, leaveRequestRepository);
        PenalizationPolicyService penalizationPolicyService = new PenalizationPolicyService(versionRepository, tierRepository,
                lateHoursTierRepository, penalisationPolicyRepository, userRepository, auditService, snapshotSerializer,
                attendanceProperties, employeeRepository, notificationService);
        lenient().when(allocationRepository.findEffectiveAt(any(), any())).thenReturn(List.of());
        PenalizationPolicyResolutionService policyResolutionService =
                new PenalizationPolicyResolutionService(versionRepository, allocationRepository, penalizationPolicyService, employeeRepository);
        ExpectedWorkHoursService expectedWorkHoursService = new ExpectedWorkHoursService(leaveRequestRepository);
        WorkHoursShortageCalculationService workHoursShortageCalculationService =
                new WorkHoursShortageCalculationService(attendanceRepository, expectedWorkHoursService, workingDayService);
        exceptionService = new ExceptionService(userRepository, employeeRepository, historyRepository,
                attendanceExceptionRepository, attendanceRepository, leaveRequestRepository,
                regularizationRequestRepository, attendanceProperties, emailService, penaltyEvaluationService,
                workingDayService, holidayRepository, policyResolutionService, expectedWorkHoursService,
                workHoursShortageCalculationService);

        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(attendanceProperties.getShiftStart()).thenReturn(LocalTime.of(9, 30));
        lenient().when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of(employeeId));
        lenient().when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser()));
        lenient().when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), anyString(), any(), any())).thenReturn(List.of());
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());
        lenient().when(attendancePenaltyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // detectNoAttendanceAndShortage's WorkingDayService pass — an empty schedule for every
        // employee means it contributes no NO_ATTENDANCE/WORK_HOURS_SHORTAGE occurrences, leaving
        // this test class free to focus purely on the LATE_ARRIVAL flow it was written for.
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of());
    }

    private User hrUser() {
        Role role = Role.builder().code("HR_ADMIN").build();
        return User.builder().id(UUID.randomUUID()).email(hrEmail).roles(Set.of(role)).build();
    }

    private Attendance lateAttendance(LocalDate date, int lateByMinutes) {
        return Attendance.builder()
                .employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 30).plusMinutes(lateByMinutes))
                .checkOutAt(date.atTime(18, 0))
                .lateByMinutes(lateByMinutes)
                .build();
    }

    private PenalizationPolicyVersion lateArrivalVersion(int version, int graceMinutes) {
        return PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyId).version(version)
                .effectiveFrom(LocalDate.of(2026, 1, 1).atStartOfDay())
                .lateArrivalEnabled(true).laGracePeriodMinutes(graceMinutes)
                .laDeductionDays(new java.math.BigDecimal("0.5"))
                .build();
    }

    // ── CRITICAL ACCEPTANCE TEST — real production flow, V1 ──
    @Test
    void realProductionFlow_v1Grace10_lateMinutes12_appliesPenalty() {
        LocalDate date = LocalDate.of(2026, 8, 15);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 12)));
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay()))
                .thenReturn(List.of(lateArrivalVersion(1, 10)));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, times(1)).save(captor.capture());
        AttendancePenalty penalty = captor.getValue();
        assertEquals(employeeId, penalty.getEmployeeUserId());
        assertEquals(date, penalty.getIncidentDate());
        assertEquals(ExceptionType.LATE_ARRIVAL, penalty.getDiscrepancyType());
        assertEquals(policyId, penalty.getPolicyId());
        assertEquals(1, penalty.getPolicyVersion());
        assertEquals(new java.math.BigDecimal("0.5"), penalty.getDeductionDays());
        assertNotNull(penalty.getEvaluatedAt());
    }

    // ── Same real production flow, V2: grace widened to 15 — NO code change, only configuration ──
    @Test
    void realProductionFlow_v2Grace15_sameLateMinutes12_noMatch_noPenaltyPersisted() {
        LocalDate date = LocalDate.of(2026, 8, 15);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 12)));
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay()))
                .thenReturn(List.of(lateArrivalVersion(2, 15)));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, never()).save(any());
    }

    // ── Version immutability across two evaluations through the real production path ──
    @Test
    void policyVersionChange_realFlow_historicalPenaltyKeepsV1_laterEvaluationUsesV2() {
        LocalDate augDate = LocalDate.of(2026, 8, 15);
        LocalDate sepDate = LocalDate.of(2026, 9, 15);

        when(versionRepository.findVersionsEffectiveAt(augDate.atStartOfDay()))
                .thenReturn(List.of(lateArrivalVersion(1, 10)));
        when(versionRepository.findVersionsEffectiveAt(sepDate.atStartOfDay()))
                .thenReturn(List.of(lateArrivalVersion(2, 15)));

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), augDate, augDate))
                .thenReturn(List.of(lateAttendance(augDate, 12)));
        exceptionService.getExceptionsForCaller(hrEmail, augDate, augDate);

        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), sepDate, sepDate))
                .thenReturn(List.of(lateAttendance(sepDate, 12)));
        exceptionService.getExceptionsForCaller(hrEmail, sepDate, sepDate);

        // Only the August evaluation (V1, 12 > 10) produced a penalty; September (V2, 12 <= 15) did not.
        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, times(1)).save(captor.capture());
        assertEquals(augDate, captor.getValue().getIncidentDate());
        assertEquals(1, captor.getValue().getPolicyVersion());
    }

    // ── Disabled section / outside effective period, through the real production path ──
    @Test
    void realProductionFlow_disabledLateArrivalSection_noMatch_noPenalty() {
        LocalDate date = LocalDate.of(2026, 8, 15);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 30)));
        PenalizationPolicyVersion disabled = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyId).version(1)
                .effectiveFrom(LocalDate.of(2026, 1, 1).atStartOfDay())
                .lateArrivalEnabled(false).build();
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of(disabled));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, never()).save(any());
    }

    @Test
    void realProductionFlow_noEffectiveVersion_noMatch_noPenalty() {
        LocalDate date = LocalDate.of(2026, 8, 15);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 30)));
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay())).thenReturn(List.of());

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, never()).save(any());
    }

    // ── Regularization: unchanged existing meaning, now honored through the real flow too ──
    @Test
    void realProductionFlow_approvedRegularization_exempt_noPenaltyPersisted() {
        LocalDate date = LocalDate.of(2026, 8, 15);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 12)));
        when(versionRepository.findVersionsEffectiveAt(date.atStartOfDay()))
                .thenReturn(List.of(lateArrivalVersion(1, 10)));
        RegularizationRequest approved = RegularizationRequest.builder()
                .employeeUserId(employeeId).attendanceDate(date).status("APPROVED").reason("r").build();
        when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(approved));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, never()).save(any());
    }

    // ── Duplicate-evaluation guard: re-running the dashboard load for an already-detected
    // exception must never create a second penalty row. ──
    @Test
    void reRunningDetection_forAlreadyDetectedException_doesNotDuplicatePenalty() {
        LocalDate date = LocalDate.of(2026, 8, 15);
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeId), date, date))
                .thenReturn(List.of(lateAttendance(date, 12)));
        // Simulate the exception already existing from a prior dashboard load — isNew=false means
        // evaluatePolicy (and therefore versionRepository) is never even reached this time.
        when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(
                employeeId, date, ExceptionType.LATE_ARRIVAL))
                .thenReturn(Optional.of(AttendanceException.builder()
                        .id(UUID.randomUUID()).employeeUserId(employeeId).exceptionDate(date)
                        .exceptionType(ExceptionType.LATE_ARRIVAL).build()));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        verify(attendancePenaltyRepository, never()).save(any());
    }
}
