package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.entity.AuditLog;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AuditLogRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** YE-07: every leave job run is audited with its failed employees, and Super Admins are alerted on failures. */
@ExtendWith(MockitoExtension.class)
class LeaveJobMonitorTest {

    @Mock private AuditService auditService;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    private LeaveJobMonitor monitor;
    private final UUID failedA = UUID.randomUUID();
    private final UUID failedB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        monitor = new LeaveJobMonitor(auditService, new AuditSnapshotSerializer(new ObjectMapper()), auditLogRepository,
                employeeRepository, userRepository, notificationService);
    }

    @Test
    void aRunWithFailures_isAuditedWithTheEmployeeCodes_andEverySuperAdminIsAlerted() {
        when(employeeRepository.findCodesByUserIds(any())).thenReturn(List.of(
                new Object[]{failedA, "EMP001"}, new Object[]{failedB, "EMP002"}));
        User admin1 = User.builder().id(UUID.randomUUID()).build();
        User admin2 = User.builder().id(UUID.randomUUID()).build();
        when(userRepository.findActiveSuperAdmins()).thenReturn(List.of(admin1, admin2));

        List<String> codes = monitor.recordRun(LeaveJobMonitor.ACCRUAL, "Leave crediting", null,
                Map.of("year", 2027, "failures", 2), List.of(failedA, failedB));

        assertEquals(List.of("EMP001", "EMP002"), codes);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(isNull(), eq(LeaveJobMonitor.ACCRUAL), isNull(), isNull(), after.capture());
        assertTrue(after.getValue().contains("\"failedEmployees\":[\"EMP001\",\"EMP002\"]"), after.getValue());
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).send(eq(admin1.getId()), eq(LeaveJobMonitor.FAILURE_NOTIFICATION), anyString(), message.capture(), anyString());
        verify(notificationService).send(eq(admin2.getId()), eq(LeaveJobMonitor.FAILURE_NOTIFICATION), anyString(), anyString(), anyString());
        assertTrue(message.getValue().contains("2 employees: EMP001, EMP002"), message.getValue());
    }

    @Test
    void aCleanRun_isStillAudited_soItsLastRunIsVisible_butAlertsNobody() {
        monitor.recordRun(LeaveJobMonitor.YEAR_END, "Leave year-end carry-forward", null, Map.of("fromYear", 2026), List.of());

        verify(auditService).log(isNull(), eq(LeaveJobMonitor.YEAR_END), isNull(), isNull(), contains("\"failedEmployees\":[]"));
        verifyNoInteractions(notificationService);
    }

    @Test
    void lastRuns_readTheLatestAuditEntryOfEachJob() {
        AuditLog accrual = AuditLog.builder().id(1L).action(LeaveJobMonitor.ACCRUAL).occurredAt(Instant.parse("2027-01-01T18:45:00Z"))
                .afterState("{\"year\":2027,\"creditsAdded\":40,\"failures\":0,\"failedEmployees\":[]}").build();
        when(auditLogRepository.findByActionInOrderByOccurredAtDesc(eq(List.of(LeaveJobMonitor.ACCRUAL)), any())).thenReturn(List.of(accrual));
        when(auditLogRepository.findByActionInOrderByOccurredAtDesc(eq(List.of(LeaveJobMonitor.YEAR_END)), any())).thenReturn(List.of());

        Map<String, LeaveJobMonitor.JobRun> runs = monitor.lastRuns();

        assertEquals(1, runs.size());
        LeaveJobMonitor.JobRun run = runs.get("accrual");
        assertNull(run.actorName()); // the nightly scheduler
        assertEquals(40, run.details().get("creditsAdded"));
        assertFalse(runs.containsKey("yearEnd")); // never ran
    }
}
