package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeaveConfigDtos.YearEndRunResult;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveCarryForwardTransfer;
import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveCarryForwardTransferRepository;
import com.nforce.onehr.repository.LeaveEntitlementPolicyRepository;
import com.nforce.onehr.repository.LeaveOccurrenceRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V310 year-end: cap, lapse, expiry, pending reservations, idempotent re-runs, failure + retry, top-up. */
@ExtendWith(MockitoExtension.class)
class LeaveYearEndServiceTest {

    @Mock private LeaveTypeRepository typeRepo;
    @Mock private LeaveBalanceRepository balanceRepo;
    @Mock private LeaveCarryForwardTransferRepository transferRepo;
    @Mock private LeaveRequestRepository requestRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private LeaveEntitlementPolicyRepository policyRepo;
    @Mock private LeaveOccurrenceRepository occurrenceRepo;
    @Mock private AuditService auditService;
    @Mock private LeaveJobMonitor jobMonitor;
    @Mock private PlatformTransactionManager txManager;

    private LeaveYearEndService service;
    private final UUID emp = UUID.randomUUID();
    private final Map<String, LeaveBalance> table = new HashMap<>();
    private LeaveType annual;
    private LeaveType sick;

    // The real "today" is in 2026 (later than FROM's year-end), which is all these tests rely on.
    private static final int FROM = 2025;

    @BeforeEach
    void setUp() {
        annual = LeaveType.builder().id(UUID.randomUUID()).code("ANNUAL").name("Annual Leave")
                .carryForwardEnabled(true).carryForwardMaxDays(new BigDecimal("5")).carryForwardExpiryMonths(3).build();
        sick = LeaveType.builder().id(UUID.randomUUID()).code("SICK").name("Sick Leave").build();
        LeaveBalanceService balances = TestLeaveBalances.over(typeRepo, balanceRepo, requestRepo, policyRepo, occurrenceRepo,
                new AttendanceProperties());
        service = new LeaveYearEndService(typeRepo, balanceRepo, transferRepo, requestRepo, employeeRepo, balances,
                TestLeaveBalances.plansFromPolicies(policyRepo, typeRepo), auditService, new AuditSnapshotSerializer(new ObjectMapper()), txManager, jobMonitor);

        lenient().when(typeRepo.findById(any())).thenAnswer(inv ->
                List.of(annual, sick).stream().filter(t -> t.getId().equals(inv.getArgument(0))).findFirst());
        lenient().when(balanceRepo.findByEmployeeUserIdAndLeaveTypeIdAndYear(any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0) + ":" + inv.getArgument(1) + ":" + inv.getArgument(2))));
        lenient().when(balanceRepo.insertIfAbsent(any(), any(), anyInt(), any())).thenAnswer(inv -> {
            UUID typeId = inv.getArgument(1);
            LeaveType t = typeId.equals(annual.getId()) ? annual : sick;
            put(inv.getArgument(0), t, inv.getArgument(2), inv.getArgument(3).toString(), "0");
            return 1;
        });
        lenient().when(policyRepo.findFirstByLeaveTypeIdAndEffectiveYearLessThanEqualOrderByEffectiveYearDesc(any(), any()))
                .thenReturn(Optional.of(LeaveEntitlementPolicy.builder().days(new BigDecimal("15")).build()));
        lenient().when(transferRepo.save(any())).thenAnswer(inv -> {
            LeaveCarryForwardTransfer t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        lenient().when(transferRepo.findByEmployeeUserIdAndLeaveTypeIdAndFromYear(any(), any(), any())).thenReturn(Optional.empty());
    }

    private LeaveBalance put(UUID employee, LeaveType t, int year, String total, String used) {
        LeaveBalance b = LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(employee).leaveType(t).year(year)
                .totalDays(new BigDecimal(total)).usedDays(new BigDecimal(used)).build();
        table.put(employee + ":" + t.getId() + ":" + year, b);
        return b;
    }

    private void population(List<UUID> employees, LeaveType... types) {
        when(employeeRepo.findActiveEmployeeUserIds()).thenReturn(employees);
        when(typeRepo.findAll()).thenReturn(List.of(types));
        when(transferRepo.findByFromYear(FROM)).thenReturn(List.of());
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private LeaveCarryForwardTransfer savedTransfer() {
        ArgumentCaptor<LeaveCarryForwardTransfer> c = ArgumentCaptor.forClass(LeaveCarryForwardTransfer.class);
        verify(transferRepo, atLeastOnce()).save(c.capture());
        return c.getValue();
    }

    @Test
    void theNightlyRun_noLongerClosesTheYear_onlyAppliesExpiries() {
        put(emp, annual, FROM, "15", "5"); // 10 unused, but nothing is transferred without HR's confirmation (LV-05)
        when(balanceRepo.findWithCarriedForwardExpiredBefore(any())).thenReturn(List.of());

        YearEndRunResult r = service.run(FROM, null);

        assertEquals(0, r.transfersCreated());
        verify(transferRepo, never()).save(any());
        assertNull(table.get(emp + ":" + annual.getId() + ":" + (FROM + 1)));
        verify(jobMonitor).recordRun(eq(LeaveJobMonitor.YEAR_END), any(), isNull(), any(), eq(java.util.Set.of()));
    }

    @Test
    void cannotProcessAYearThatHasNotEnded() {
        int current = LocalDate.now().getYear() + 1;
        assertThrows(IllegalArgumentException.class, () -> service.run(current, null));
    }

    @Test
    void expiry_marksUnusedCarriedForwardExpired_keepsWhatPendingLeaveBeforeExpiryClaims_andIsIdempotent() {
        LeaveBalance b = put(emp, annual, FROM + 1, "15", "0");
        b.setCarriedForwardDays(new BigDecimal("5"));
        b.setCarriedForwardUsedDays(new BigDecimal("1"));
        b.setCarriedForwardExpiresOn(LocalDate.of(FROM + 1, 3, 31));
        when(balanceRepo.findById(b.getId())).thenReturn(Optional.of(b));
        when(requestRepo.findPendingOverlapping(eq(emp), eq(annual.getId()), any(), any())).thenReturn(List.of(
                LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(emp).leaveType(annual)
                        .startDate(LocalDate.of(FROM + 1, 3, 30)).endDate(LocalDate.of(FROM + 1, 3, 30))
                        .totalDays(BigDecimal.ONE).status("PENDING").build()));

        assertTrue(service.expire(b.getId(), null));
        assertDays("3", b.getCarriedForwardExpiredDays()); // 5 - 1 used - 1 still claimed by pending leave
        verify(auditService).log(isNull(), eq("LEAVE_CARRY_FORWARD_EXPIRED"), eq(b.getId()), any(), any());

        assertFalse(service.expire(b.getId(), null)); // nothing new to expire
        assertDays("3", b.getCarriedForwardExpiredDays());
    }

    @Test
    void rejectionAfterYearEnd_topsUpTheTransfer_upToTheCap() {
        annual.setCarryForwardExpiryMonths(null); // never expires, so a top-up is still allowed
        LeaveBalance from = put(emp, annual, FROM, "15", "11"); // 4 unused now that the pending request was rejected
        LeaveBalance next = put(emp, annual, FROM + 1, "15", "0");
        next.setCarriedForwardDays(new BigDecimal("2"));
        LeaveCarryForwardTransfer t = LeaveCarryForwardTransfer.builder().id(UUID.randomUUID()).employeeUserId(emp)
                .leaveType(annual).fromYear(FROM).unusedDays(new BigDecimal("2")).carriedDays(new BigDecimal("2"))
                .lapsedDays(BigDecimal.ZERO).build();
        when(transferRepo.findByEmployeeUserIdAndLeaveTypeIdAndFromYear(emp, annual.getId(), FROM)).thenReturn(Optional.of(t));

        service.refreshTransfers(emp, annual, LocalDate.of(FROM, 12, 29), LocalDate.of(FROM, 12, 30));

        assertDays("4", t.getCarriedDays());
        assertDays("4", next.getCarriedForwardDays());
        assertNotNull(from);
    }

    @Test
    void topUp_keepsTheEncashedDays_andNeverTouchesATransferHrAdjusted() {
        annual.setCarryForwardExpiryMonths(null);
        put(emp, annual, FROM, "15", "9"); // 6 unused now; 2 were encashed at year-end
        LeaveBalance next = put(emp, annual, FROM + 1, "15", "0");
        next.setCarriedForwardDays(new BigDecimal("2"));
        LeaveCarryForwardTransfer t = LeaveCarryForwardTransfer.builder().id(UUID.randomUUID()).employeeUserId(emp)
                .leaveType(annual).fromYear(FROM).unusedDays(new BigDecimal("4")).carriedDays(new BigDecimal("2"))
                .encashedDays(new BigDecimal("2")).lapsedDays(BigDecimal.ZERO).build();
        when(transferRepo.findByEmployeeUserIdAndLeaveTypeIdAndFromYear(emp, annual.getId(), FROM)).thenReturn(Optional.of(t));

        service.refreshTransfers(emp, annual, LocalDate.of(FROM, 12, 29), LocalDate.of(FROM, 12, 30));
        assertDays("4", t.getCarriedDays()); // 6 unused − 2 encashed, within the cap of 5
        assertDays("2", t.getEncashedDays());
        assertDays("0", t.getLapsedDays());

        t.setAdjusted(true);
        put(emp, annual, FROM, "15", "8");
        service.refreshTransfers(emp, annual, LocalDate.of(FROM, 12, 29), LocalDate.of(FROM, 12, 30));
        assertDays("4", t.getCarriedDays());
    }
}
