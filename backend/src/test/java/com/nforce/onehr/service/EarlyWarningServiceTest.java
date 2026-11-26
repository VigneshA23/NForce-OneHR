package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.entity.PenalizationPolicyWorkHoursTier;
import com.nforce.onehr.entity.PenaltyEarlyWarning;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AttendanceExceptionRepository;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PenalizationPolicyWorkHoursTierRepository;
import com.nforce.onehr.repository.PenaltyEarlyWarningRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PN-03: early-warning notifications - thresholds, config switches and de-duplication. */
@ExtendWith(MockitoExtension.class)
class EarlyWarningServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private PenalizationPolicyResolutionService policyResolutionService;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private AttendanceExceptionRepository attendanceExceptionRepository;
    @Mock private PenalizationPolicyWorkHoursTierRepository tierRepository;
    @Mock private WorkHoursShortageCalculationService shortageCalculationService;
    @Mock private PenaltyEarlyWarningRepository warningRepository;
    @Mock private NotificationService notificationService;
    @Mock private EmailService emailService;
    @Mock private AttendanceProperties attendanceProperties;
    @Mock private PlatformTransactionManager transactionManager;
    @InjectMocks private EarlyWarningService service;

    private final UUID userId = UUID.randomUUID();
    private Employee employee;
    private PenalizationPolicyVersion policy;

    @BeforeEach
    void setUp() {
        User user = mock(User.class);
        lenient().when(user.getEmail()).thenReturn("emp@x.com");
        employee = Employee.builder().userId(userId).fullName("Emp One").user(user).build();
        policy = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).ewEnabled(true)
                .lateArrivalEnabled(true).laExemptCount(3).laExemptPeriod("MONTH").laDeductionDays(new BigDecimal("0.5"))
                .missingLogsEnabled(true).mlExemptDays(2).mlExemptPeriod("MONTH")
                .workHoursShortageEnabled(true).whsDeductionPeriod("WEEK")
                .effectiveFrom(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
        lenient().when(policyResolutionService.resolveEffectiveVersionForEmployee(eq(employee), any())).thenReturn(policy);
        lenient().when(warningRepository.existsByEmployeeUserIdAndWarningTypeAndPeriodStartAndThresholdKey(any(), anyString(), any(), anyString())).thenReturn(false);
    }

    private void lateMarks(long n) {
        lenient().when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(eq(userId), any(), any(), eq("LATE"))).thenReturn(n);
    }

    @Test
    void warnsInAppAndByEmailWhenOneExemptLateMarkIsLeft() {
        lateMarks(2); // 2 of 3 exempt used -> limit minus 1

        assertEquals(1, service.evaluate(employee, TODAY));

        verify(notificationService).send(eq(userId), eq("ATTENDANCE_EARLY_WARNING"), anyString(), anyString(), anyString());
        verify(emailService).sendEarlyWarningEmail(eq("emp@x.com"), eq("Emp One"), anyString(), anyString(), any(String[].class));
        verify(warningRepository).saveAndFlush(any(PenaltyEarlyWarning.class));
    }

    @Test
    void doesNotWarnBeforeTheThresholdOrOnceTheLimitIsAlreadyPassed() {
        lateMarks(1); // 2 exempt left, threshold is "1 left"
        assertEquals(0, service.evaluate(employee, TODAY));
        lateMarks(3); // all exempt used already - the next one is penalized, too late for a nudge
        assertEquals(0, service.evaluate(employee, TODAY));
        verify(notificationService, never()).send(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void thresholdIsConfigurable() {
        policy.setEwExemptRemaining(2);
        policy.setEwMissingLogsEnabled(false); // isolate the late-arrival rule
        lateMarks(1); // 2 exempt left, now within the configured threshold
        assertEquals(1, service.evaluate(employee, TODAY));
    }

    @Test
    void masterSwitchAndPerRuleSwitchTurnItOff() {
        lateMarks(2);
        policy.setEwLateArrivalEnabled(false);
        assertEquals(0, service.evaluate(employee, TODAY));
        policy.setEwLateArrivalEnabled(true);
        policy.setEwEnabled(false);
        assertEquals(0, service.evaluate(employee, TODAY));
        verify(notificationService, never()).send(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sameThresholdInTheSamePeriodIsNotRepeated() {
        lateMarks(2);
        when(warningRepository.existsByEmployeeUserIdAndWarningTypeAndPeriodStartAndThresholdKey(
                userId, "LATE_ARRIVAL", LocalDate.of(2026, 11, 1), "REMAINING_1")).thenReturn(true);
        assertEquals(0, service.evaluate(employee, TODAY));
        verify(notificationService, never()).send(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aConcurrentRunThatLostTheRaceDoesNotNotify() {
        lateMarks(2);
        when(warningRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        assertEquals(0, service.evaluate(employee, TODAY));
        verify(notificationService, never()).send(any(), anyString(), anyString(), anyString(), anyString());
        verify(emailService, never()).sendEarlyWarningEmail(any(), any(), any(), any(), any());
    }

    @Test
    void missingLogsWarnsWhenOneExemptDayIsLeft() {
        lateMarks(0);
        when(attendanceExceptionRepository.countByEmployeeUserIdAndExceptionTypeAndExceptionDateBetween(eq(userId), eq("MISSING_PUNCH"), any(), any()))
                .thenReturn(1L); // 1 of 2 used
        assertEquals(1, service.evaluate(employee, TODAY));
    }

    @Test
    void shortageWarnsWithHoursStillNeeded() {
        lateMarks(0);
        // Mon-Wed: 24h worked of 27h expected so far (88.9%); the week expects 45h. Tier "<80%" is within the 10-point margin.
        when(shortageCalculationService.computeCycleHours(eq(employee), any(), eq(policy)))
                .thenReturn(new WorkHoursShortageCalculationService.CycleHours(24 * 60, 27 * 60, 45 * 60, LocalDate.of(2026, 11, 23)));
        PenalizationPolicyWorkHoursTier tier = new PenalizationPolicyWorkHoursTier();
        tier.setThresholdPercent(new BigDecimal("80"));
        tier.setDeductionDays(new BigDecimal("0.5"));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(policy.getId())).thenReturn(List.of(tier));

        assertEquals(1, service.evaluate(employee, TODAY));

        // 80% of 45h = 36h; 24h worked -> 12h still needed
        org.mockito.ArgumentCaptor<String> msg = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService).send(eq(userId), anyString(), anyString(), msg.capture(), anyString());
        org.junit.jupiter.api.Assertions.assertTrue(msg.getValue().contains("12h 00m"), msg.getValue());
        verify(warningRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(w ->
                "WORK_HOURS_SHORTAGE".equals(w.getWarningType()) && "TIER_80".equals(w.getThresholdKey())
                        && LocalDate.of(2026, 11, 23).equals(w.getPeriodStart())));
    }

    @Test
    void shortageDoesNotWarnWhenFarAboveTheTierOrAlreadyBelowIt() {
        lateMarks(0);
        PenalizationPolicyWorkHoursTier tier = new PenalizationPolicyWorkHoursTier();
        tier.setThresholdPercent(new BigDecimal("80"));
        tier.setDeductionDays(new BigDecimal("0.5"));
        when(tierRepository.findByPolicyVersionIdOrderBySortOrderAsc(policy.getId())).thenReturn(List.of(tier));
        // 100% - comfortably above
        when(shortageCalculationService.computeCycleHours(eq(employee), any(), eq(policy)))
                .thenReturn(new WorkHoursShortageCalculationService.CycleHours(27 * 60, 27 * 60, 45 * 60, LocalDate.of(2026, 11, 23)));
        assertEquals(0, service.evaluate(employee, TODAY));
        // 70% - already below the tier, the penalty engine owns it now
        when(shortageCalculationService.computeCycleHours(eq(employee), any(), eq(policy)))
                .thenReturn(new WorkHoursShortageCalculationService.CycleHours(19 * 60, 27 * 60, 45 * 60, LocalDate.of(2026, 11, 23)));
        assertEquals(0, service.evaluate(employee, TODAY));
    }

    @Test
    void perDayShortagePeriodIsNotForewarned() {
        lateMarks(0);
        when(shortageCalculationService.computeCycleHours(eq(employee), any(), eq(policy))).thenReturn(null);
        assertEquals(0, service.evaluate(employee, TODAY));
    }
}
