package com.nforce.onehr.service;

import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.PenalizationPolicyVersion;
import com.nforce.onehr.repository.AttendanceRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the double-penalization fix: this legacy every-3rd-late-arrival deduction must defer to
 * the configurable Penalization Policy engine whenever that engine already covers late arrival for
 * the employee/date, and only run standalone when it doesn't (no applicable policy, or one with
 * Late Arrival disabled) — see the class javadoc on {@link LatePenaltyService}.
 */
@ExtendWith(MockitoExtension.class)
class LatePenaltyServiceTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private LeaveBalanceRepository leaveBalanceRepository;
    @Mock private LeaveTypeRepository leaveTypeRepository;
    @Mock private AuditService auditService;
    @Mock private AuditSnapshotSerializer auditSnapshot;
    @Mock private NotificationService notificationService;
    @Mock private PenalizationPolicyResolutionService penalizationPolicyResolutionService;

    @InjectMocks private LatePenaltyService latePenaltyService;

    private final UUID employeeId = UUID.randomUUID();
    private final LocalDate workDate = LocalDate.of(2026, 8, 10);
    private Employee employee;

    @BeforeEach
    void setUp() {
        employee = Employee.builder().userId(employeeId).fullName("Test Employee").build();
        lenient().when(auditSnapshot.toJson(any())).thenReturn("{}");
    }

    private void stubThirdLateArrivalThisMonth() {
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(
                employeeId, workDate.withDayOfMonth(1), workDate.withDayOfMonth(workDate.lengthOfMonth()), "LATE"))
                .thenReturn(3L);
        LeaveType casual = LeaveType.builder().id(UUID.randomUUID()).code("CASUAL").name("Casual Leave").build();
        when(leaveTypeRepository.findByCode("CASUAL")).thenReturn(Optional.of(casual));
        when(leaveBalanceRepository.findByEmployeeUserIdAndLeaveTypeIdAndYear(employeeId, casual.getId(), workDate.getYear()))
                .thenReturn(Optional.of(LeaveBalance.builder().employeeUserId(employeeId).leaveType(casual)
                        .year(workDate.getYear()).totalDays(new BigDecimal("20")).usedDays(BigDecimal.ZERO).build()));
    }

    @Test
    void appliesLegacyPenalty_whenNoConfiguredPolicyIsApplicable() {
        when(penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, workDate)).thenReturn(null);
        stubThirdLateArrivalThisMonth();

        latePenaltyService.applyIfDue(employee, workDate);

        verify(leaveBalanceRepository).save(any(LeaveBalance.class));
    }

    @Test
    void appliesLegacyPenalty_whenApplicablePolicyHasLateArrivalDisabled() {
        PenalizationPolicyVersion lateArrivalDisabled = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1).lateArrivalEnabled(false).build();
        when(penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, workDate))
                .thenReturn(lateArrivalDisabled);
        stubThirdLateArrivalThisMonth();

        latePenaltyService.applyIfDue(employee, workDate);

        verify(leaveBalanceRepository).save(any(LeaveBalance.class));
    }

    @Test
    void skipsLegacyPenalty_whenApplicablePolicyHasLateArrivalEnabled() {
        PenalizationPolicyVersion lateArrivalEnabled = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1).lateArrivalEnabled(true).build();
        when(penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, workDate))
                .thenReturn(lateArrivalEnabled);

        latePenaltyService.applyIfDue(employee, workDate);

        verify(leaveBalanceRepository, never()).save(any());
        verify(attendanceRepository, never()).countByEmployeeUserIdAndWorkDateBetweenAndStatus(any(), any(), any(), any());
    }

    @Test
    void skipsLegacyPenalty_evenWhenThirdLateArrivalWouldOtherwiseBeDue_ifConfiguredPolicyCoversIt() {
        // Proves the gate short-circuits BEFORE the every-3rd-arrival count check runs at all —
        // not merely that it happens to skip on an unrelated count.
        PenalizationPolicyVersion lateArrivalEnabled = PenalizationPolicyVersion.builder()
                .id(UUID.randomUUID()).policyId(UUID.randomUUID()).version(1).lateArrivalEnabled(true).build();
        when(penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, workDate))
                .thenReturn(lateArrivalEnabled);
        lenient().when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(any(), any(), any(), any()))
                .thenReturn(3L);

        latePenaltyService.applyIfDue(employee, workDate);

        verify(notificationService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void legacyPenalty_stillSkippedWhenNoCasualBalanceConfigured_regardlessOfPolicyGate() {
        when(penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, workDate)).thenReturn(null);
        when(attendanceRepository.countByEmployeeUserIdAndWorkDateBetweenAndStatus(
                employeeId, workDate.withDayOfMonth(1), workDate.withDayOfMonth(workDate.lengthOfMonth()), "LATE"))
                .thenReturn(3L);
        when(leaveTypeRepository.findByCode("CASUAL")).thenReturn(Optional.empty());

        latePenaltyService.applyIfDue(employee, workDate);

        verify(leaveBalanceRepository, never()).save(any());
    }

    @Test
    void appliesLegacyPenalty_addsHalfDayToUsedDays() {
        when(penalizationPolicyResolutionService.resolveEffectiveVersionForEmployee(employee, workDate)).thenReturn(null);
        stubThirdLateArrivalThisMonth();

        latePenaltyService.applyIfDue(employee, workDate);

        org.mockito.ArgumentCaptor<LeaveBalance> captor = org.mockito.ArgumentCaptor.forClass(LeaveBalance.class);
        verify(leaveBalanceRepository).save(captor.capture());
        assertEquals(new BigDecimal("0.5"), captor.getValue().getUsedDays());
    }
}
