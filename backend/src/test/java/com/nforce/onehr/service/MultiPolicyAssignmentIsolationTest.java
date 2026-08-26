package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.entity.*;
import com.nforce.onehr.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Final-verification gap fix: confirms two employees, each assigned to a DIFFERENT
 * {@link PenalisationPolicy}, are evaluated against their own policy's configuration only — and
 * that the unscoped, cross-policy {@code findVersionsEffectiveAt} lookup is never consulted for
 * either of them. This is the exact scenario Section 36 ("Employee → Assigned Policy → Applicable
 * Version") requires and Policy List (Section 5) makes possible for the first time — before this
 * fix, an employee with no explicit assignment fell through to that unscoped query, which becomes
 * ambiguous (not just "unused") the moment a second policy exists, since it orders by version
 * number across every policy combined.
 */
@ExtendWith(MockitoExtension.class)
class MultiPolicyAssignmentIsolationTest {

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
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;
    @Mock private PenalizationPolicyLateHoursTierRepository lateHoursTierRepository;
    @Mock private AttendancePenaltyRepository attendancePenaltyRepository;
    @Mock private PenalisationPolicyRepository penalisationPolicyRepository;
    @Mock private AuditService auditService;

    private ExceptionService exceptionService;

    private final UUID employeeAId = UUID.randomUUID();
    private final UUID employeeBId = UUID.randomUUID();
    private final UUID policyAId = UUID.randomUUID();
    private final UUID policyBId = UUID.randomUUID();
    private final String hrEmail = "hr@test.com";
    private final LocalDate date = LocalDate.of(2026, 8, 10); // a Monday

    @BeforeEach
    void setUp() {
        AttendancePolicyEngine policyEngine = new ConfiguredAttendancePolicyEngine(versionRepository, tierRepository, lateHoursTierRepository);
        AuditSnapshotSerializer snapshotSerializer = new AuditSnapshotSerializer(new ObjectMapper());
        PenaltyDeductionService penaltyDeductionService = new PenaltyDeductionService(leaveTypeRepository, leaveBalanceRepository, snapshotSerializer);
        AttendancePenaltyEvaluationService penaltyEvaluationService =
                new AttendancePenaltyEvaluationService(policyEngine, attendancePenaltyRepository, penaltyDeductionService);
        WorkingDayService workingDayService = new WorkingDayService(holidayRepository, leaveRequestRepository);
        PenalizationPolicyService penalizationPolicyService = new PenalizationPolicyService(versionRepository, tierRepository,
                lateHoursTierRepository, penalisationPolicyRepository, userRepository, auditService, snapshotSerializer, attendanceProperties);
        exceptionService = new ExceptionService(userRepository, employeeRepository, historyRepository,
                attendanceExceptionRepository, attendanceRepository, leaveRequestRepository,
                regularizationRequestRepository, attendanceProperties, emailService, penaltyEvaluationService,
                workingDayService, versionRepository, holidayRepository, penalizationPolicyService);

        lenient().when(attendanceProperties.getZone()).thenReturn("Asia/Kolkata");
        lenient().when(attendanceProperties.getShiftStart()).thenReturn(LocalTime.of(9, 30));
        lenient().when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of(employeeAId, employeeBId));
        lenient().when(userRepository.findByEmail(hrEmail)).thenReturn(Optional.of(hrUser()));
        lenient().when(leaveRequestRepository.findByEmployeeUserIdInAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                any(), anyString(), any(), any())).thenReturn(List.of());
        lenient().when(regularizationRequestRepository.findByEmployeeUserIdInAndAttendanceDateBetween(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdAndExceptionDateAndExceptionType(any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(attendanceExceptionRepository.findByEmployeeUserIdInAndExceptionDateBetweenOrderByExceptionDateDescCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());
        lenient().when(attendanceExceptionRepository.existsByEmployeeUserIdAndExceptionDateAndExceptionType(any(), any(), any()))
                .thenReturn(false);
        lenient().when(attendanceExceptionRepository.countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(any(), any(), any(), any()))
                .thenReturn(0L);
        lenient().when(attendancePenaltyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(employeeRepository.findAllByIdWithScheduleDetails(any())).thenReturn(List.of());
    }

    private User hrUser() {
        Role role = Role.builder().code("HR_ADMIN").build();
        return User.builder().id(UUID.randomUUID()).email(hrEmail).roles(Set.of(role)).build();
    }

    private Employee employee(UUID id, PenalisationPolicy policy) {
        User user = User.builder().id(id).email(id + "@test.com").build();
        return Employee.builder().userId(id).user(user).employeeCode("NF-" + id).fullName("Employee " + id)
                .joiningDate(date.minusYears(1)).penalisationPolicy(policy).build();
    }

    private Attendance lateAttendance(UUID employeeId, int lateByMinutes) {
        return Attendance.builder().employeeUserId(employeeId).workDate(date)
                .checkInAt(date.atTime(9, 30).plusMinutes(lateByMinutes)).checkOutAt(date.atTime(18, 0))
                .lateByMinutes(lateByMinutes).build();
    }

    @Test
    void employeeA_evaluatedAgainstPolicyA_employeeB_evaluatedAgainstPolicyB_noGlobalFallback() {
        PenalisationPolicy policyA = PenalisationPolicy.builder().id(policyAId).name("Policy A").build();
        PenalisationPolicy policyB = PenalisationPolicy.builder().id(policyBId).name("Policy B").build();
        when(employeeRepository.findById(employeeAId)).thenReturn(Optional.of(employee(employeeAId, policyA)));
        when(employeeRepository.findById(employeeBId)).thenReturn(Optional.of(employee(employeeBId, policyB)));

        // Both employees are 20 minutes late on the same day. The scope list's order isn't
        // guaranteed (it's built from a Set), so match on any list rather than a fixed order.
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(anyList(), eq(date), eq(date)))
                .thenReturn(List.of(lateAttendance(employeeAId, 20), lateAttendance(employeeBId, 20)));

        // Policy A: strict — 5 min grace, 1 day deduction. Policy B: lenient — 60 min grace.
        PenalizationPolicyVersion versionA = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyAId).version(1)
                .effectiveFrom(date.minusMonths(1).atStartOfDay())
                .lateArrivalEnabled(true).laGracePeriodMinutes(5).laDeductionDays(BigDecimal.ONE).build();
        PenalizationPolicyVersion versionB = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyBId).version(1)
                .effectiveFrom(date.minusMonths(1).atStartOfDay())
                .lateArrivalEnabled(true).laGracePeriodMinutes(60).laDeductionDays(new BigDecimal("2")).build();
        when(versionRepository.findVersionsEffectiveAtForPolicy(policyAId, date.atStartOfDay())).thenReturn(List.of(versionA));
        when(versionRepository.findVersionsEffectiveAtForPolicy(policyBId, date.atStartOfDay())).thenReturn(List.of(versionB));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        // Only Employee A's lateness (20 > 5 min grace) breaches their own policy; Employee B's
        // identical lateness is within THEIR policy's more lenient 60-minute grace.
        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, times(1)).save(captor.capture());
        AttendancePenalty penalty = captor.getValue();
        assertEquals(employeeAId, penalty.getEmployeeUserId());
        assertEquals(policyAId, penalty.getPolicyId());
        assertEquals(BigDecimal.ONE, penalty.getDeductionDays());

        // Critical: the unscoped, cross-policy lookup must never be consulted for either employee
        // once each has an explicit assignment — no accidental global-policy fallback.
        verify(versionRepository, never()).findVersionsEffectiveAt(any());
    }

    @Test
    void employeeWithNoAssignment_fallsBackToOrgDefaultPolicy_deterministically() {
        // No penalisationPolicy set — simulates a newly-created employee nobody has assigned yet.
        when(employeeRepository.findById(employeeAId)).thenReturn(Optional.of(employee(employeeAId, null)));
        when(attendanceRepository.findByEmployeeUserIdInAndWorkDateBetween(List.of(employeeAId), date, date))
                .thenReturn(List.of(lateAttendance(employeeAId, 20)));
        lenient().when(userRepository.findEmployeeRoleUserIds()).thenReturn(Set.of(employeeAId));

        PenalisationPolicy defaultPolicy = PenalisationPolicy.builder().id(policyAId).name("Default Tracking Policy")
                .createdAt(java.time.LocalDateTime.of(2020, 1, 1, 0, 0)).build();
        when(penalisationPolicyRepository.findAll()).thenReturn(List.of(defaultPolicy));
        PenalizationPolicyVersion defaultVersion = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(policyAId).version(1)
                .effectiveFrom(date.minusMonths(1).atStartOfDay())
                .lateArrivalEnabled(true).laGracePeriodMinutes(5).laDeductionDays(BigDecimal.ONE).build();
        when(versionRepository.findVersionsEffectiveAtForPolicy(policyAId, date.atStartOfDay())).thenReturn(List.of(defaultVersion));

        exceptionService.getExceptionsForCaller(hrEmail, date, date);

        ArgumentCaptor<AttendancePenalty> captor = ArgumentCaptor.forClass(AttendancePenalty.class);
        verify(attendancePenaltyRepository, times(1)).save(captor.capture());
        assertEquals(policyAId, captor.getValue().getPolicyId(), "unassigned employee resolves to the deterministic org default, not an arbitrary policy");
        // Still never falls through to the ambiguous unscoped lookup.
        verify(versionRepository, never()).findVersionsEffectiveAt(any());
    }
}
