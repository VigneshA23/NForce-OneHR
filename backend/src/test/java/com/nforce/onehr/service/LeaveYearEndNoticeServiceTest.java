package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.entity.AuditLog;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.AuditLogRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** YE-05: year-end reminders, manager digest and new-year balance notices. */
@ExtendWith(MockitoExtension.class)
class LeaveYearEndNoticeServiceTest {

    @Mock private LeaveBalanceService balances;
    @Mock private LeaveTypeRepository typeRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private EmployeeManagerHistoryRepository historyRepo;
    @Mock private NotificationService notifications;
    @Mock private EmailService email;
    @Mock private AuditService audit;
    @Mock private AuditLogRepository auditLogRepo;

    private LeaveYearEndNoticeService service;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID manager = UUID.randomUUID();
    private final LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave")
            .carryForwardEnabled(true).carryForwardMaxDays(new BigDecimal("5")).carryForwardExpiryMonths(3).build();

    @BeforeEach
    void setUp() {
        service = new LeaveYearEndNoticeService(balances, typeRepo, employeeRepo, historyRepo, notifications, email, audit,
                new AuditSnapshotSerializer(new ObjectMapper()), auditLogRepo,
                true, true, "12-01,12-15", "12-01", "01-01", new BigDecimal("5"));
        lenient().when(employeeRepo.findActiveNoticeRecipients()).thenReturn(List.of(
                new Object[]{alice, "Alice", "alice@test.com"}, new Object[]{bob, "Bob", "bob@test.com"},
                new Object[]{manager, "Mona", "mona@test.com"}));
        lenient().when(auditLogRepo.findByActionInOrderByOccurredAtDesc(any(), any())).thenReturn(List.of());
        lenient().when(typeRepo.findAll()).thenReturn(List.of(paid));
        lenient().when(email.sendLeaveNoticeEmail(any(), any(), any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(true));
    }

    private LeaveBalanceResponse balance(String remaining) {
        return LeaveBalanceResponse.builder().leaveTypeId(paid.getId()).leaveTypeName("Paid Leave").entitlementBasis(LeaveType.BASIS_ANNUAL)
                .year(2026).totalDays(new BigDecimal("15")).remainingDays(new BigDecimal(remaining)).build();
    }

    @Test
    void onReminderDate_everyoneWithUnusedLeaveIsTold_whatTheCarryForwardSettingDoes() {
        when(balances.balancesFor(alice, 2026)).thenReturn(List.of(balance("8")));
        when(balances.balancesFor(bob, 2026)).thenReturn(List.of(balance("0")));
        when(balances.balancesFor(manager, 2026)).thenReturn(List.of());

        Map<String, Integer> sent = service.runFor(LocalDate.of(2026, 12, 15));

        assertEquals(Map.of(LeaveYearEndNoticeService.REMINDER, 1), sent); // 15 Dec: no manager digest
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).send(eq(alice), eq("LEAVE_YEAR_END_REMINDER"), anyString(), message.capture(), eq("/leave"));
        assertEquals("You have 8 days of Paid Leave left in 2026. After 31 Dec 2026, under the current policy, "
                + "up to 5 days carry forward to 2027 (to be used by 31 Mar 2027) and the rest lapse.", message.getValue());
        verify(notifications, never()).send(eq(bob), any(), any(), any(), any()); // nothing unused
        verify(email).sendLeaveNoticeEmail(eq("alice@test.com"), eq("Alice"), anyString(), anyString(), any(), eq("/leave"));
        verify(audit).log(isNull(), eq(LeaveYearEndNoticeService.SENT_ACTION), isNull(), isNull(), contains("\"kind\":\"REMINDER\""));
    }

    @Test
    void lapseIsTheWording_whenCarryForwardIsOff() {
        LeaveType lapsing = LeaveType.builder().name("Paid Leave").carryForwardEnabled(false).build();
        assertEquals("unused days lapse", LeaveYearEndNoticeService.policyText(lapsing, 2026));
    }

    @Test
    void onTheFirstOfDecember_managersGetOneDigest_ofReportsAboveTheThreshold() {
        when(balances.balancesFor(alice, 2026)).thenReturn(List.of(balance("8")));
        when(balances.balancesFor(bob, 2026)).thenReturn(List.of(balance("4"))); // not above 5
        when(balances.balancesFor(manager, 2026)).thenReturn(List.of());
        when(historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(alice))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder().employeeUserId(alice).managerUserId(manager).build()));

        Map<String, Integer> sent = service.runFor(LocalDate.of(2026, 12, 1));

        assertEquals(1, sent.get(LeaveYearEndNoticeService.MANAGER_DIGEST));
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications).send(eq(manager), eq("LEAVE_TEAM_UNUSED_DIGEST"), anyString(), message.capture(), eq("/my-team"));
        assertTrue(message.getValue().startsWith("1 of your team has more than 5 days of leave left in 2026: Alice (8 days)."),
                message.getValue());
    }

    @Test
    void aKindAlreadySentToday_isNotSentAgain() {
        when(auditLogRepo.findByActionInOrderByOccurredAtDesc(any(), any())).thenReturn(List.of(AuditLog.builder()
                .action(LeaveYearEndNoticeService.SENT_ACTION).afterState("{\"kind\":\"REMINDER\",\"date\":\"2026-12-15\",\"recipients\":3}").build()));

        assertTrue(service.runFor(LocalDate.of(2026, 12, 15)).isEmpty());
        verifyNoInteractions(notifications, email, balances);
    }

    @Test
    void anOrdinaryDay_sendsNothing() {
        assertTrue(service.runFor(LocalDate.of(2026, 11, 25)).isEmpty());
        verifyNoInteractions(notifications, email, balances);
    }

    @Test
    void onNewYearsDay_eachEmployeeGetsTheirNewQuota_andAnyCarriedForwardDays() {
        LeaveBalanceResponse b = LeaveBalanceResponse.builder().leaveTypeName("Paid Leave").entitlementBasis(LeaveType.BASIS_ANNUAL)
                .year(2027).creditFrequency("MONTHLY").totalDays(new BigDecimal("1.25")).annualQuotaDays(new BigDecimal("15"))
                .carriedForwardDays(new BigDecimal("5")).carriedForwardExpiresOn(LocalDate.of(2027, 3, 31)).build();
        when(balances.balancesFor(any(), eq(2027))).thenReturn(List.of(b));

        Map<String, Integer> sent = service.runFor(LocalDate.of(2027, 1, 1));

        assertEquals(3, sent.get(LeaveYearEndNoticeService.NEW_YEAR));
        verify(notifications).send(eq(alice), eq("LEAVE_NEW_YEAR_BALANCE"), eq("Your 2027 leave balance"),
                eq("Paid Leave: 15 days for 2027, credited monthly (1.25 days credited so far), plus 5 days carried forward from 2026 to use by 31 Mar 2027."),
                eq("/leave"));
    }
}
