package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeaveOccurrence;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.entity.LeaveTypeClassification;
import com.nforce.onehr.repository.LeaveBalanceRepository;
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

/**
 * V309 balance engine: independent per-type balances, configured entitlements, unlimited unpaid,
 * Maternity per occurrence, carried-forward bucket with expiry and priority, pending reservations
 * per leave year, cross-year splits, penalty debit/credit.
 */
@ExtendWith(MockitoExtension.class)
class LeaveBalanceServiceTest {

    @Mock private LeaveTypeRepository typeRepo;
    @Mock private LeaveBalanceRepository balanceRepo;
    @Mock private LeaveRequestRepository requestRepo;
    @Mock private LeaveEntitlementPolicyRepository policyRepo;
    @Mock private LeaveOccurrenceRepository occurrenceRepo;

    private LeaveBalanceService service;
    private final UUID emp = UUID.randomUUID();
    private final LeaveType annual = paidType("ANNUAL", "Annual Leave");
    private final LeaveType sick = paidType("SICK", "Sick Leave");
    private final LeaveType unpaid = LeaveType.builder().id(UUID.randomUUID()).code("LOP").name("Loss of Pay")
            .classification(LeaveTypeClassification.UNPAID).build();
    private final LeaveType maternity = LeaveType.builder().id(UUID.randomUUID()).code("MATERNITY").name("Maternity Leave")
            .entitlementBasis(LeaveType.BASIS_PER_OCCURRENCE).occurrenceWindowMonths(12).build();
    /** (typeId:year) -> row; stands in for the leave_balances table. */
    private final Map<String, LeaveBalance> table = new HashMap<>();

    // Far-future years keep "today" (used only for expiry openness) out of the picture.
    private static final int Y = 2031;

    @BeforeEach
    void setUp() {
        service = new LeaveBalanceService(typeRepo, balanceRepo, requestRepo, policyRepo, occurrenceRepo, new AttendanceProperties());
        lenient().when(balanceRepo.findByEmployeeUserIdAndLeaveTypeIdAndYear(any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(1) + ":" + inv.getArgument(2))));
        lenient().when(balanceRepo.insertIfAbsent(any(), any(), anyInt(), any())).thenAnswer(inv -> {
            UUID typeId = inv.getArgument(1);
            LeaveType t = List.of(annual, sick).stream().filter(x -> x.getId().equals(typeId)).findFirst().orElseThrow();
            table.putIfAbsent(typeId + ":" + inv.getArgument(2), LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(emp)
                    .leaveType(t).year(inv.getArgument(2)).totalDays(inv.getArgument(3)).usedDays(BigDecimal.ZERO).build());
            return 1;
        });
        lenient().when(occurrenceRepo.save(any(LeaveOccurrence.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(policyRepo.findFirstByLeaveTypeIdAndEffectiveYearLessThanEqualOrderByEffectiveYearDesc(any(), any()))
                .thenAnswer(inv -> Optional.of(LeaveEntitlementPolicy.builder()
                        .days(maternity.getId().equals(inv.getArgument(0)) ? new BigDecimal("182") : new BigDecimal("15")).build()));
    }

    private static LeaveType paidType(String code, String name) {
        return LeaveType.builder().id(UUID.randomUUID()).code(code).name(name).build();
    }

    private LeaveBalance put(LeaveType t, int year, String total, String used) {
        LeaveBalance b = LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(emp).leaveType(t).year(year)
                .totalDays(new BigDecimal(total)).usedDays(new BigDecimal(used)).build();
        table.put(t.getId() + ":" + year, b);
        return b;
    }

    private LeaveRequest pending(LeaveType t, LocalDate start, LocalDate end, String days) {
        return LeaveRequest.builder().id(UUID.randomUUID()).employeeUserId(emp).leaveType(t)
                .startDate(start).endDate(end).totalDays(new BigDecimal(days)).status("PENDING").build();
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    // ── leave year ──

    @Test
    void daysByYear_splitsACrossYearRequestPerLeaveYear() {
        Map<Integer, BigDecimal> split = LeaveBalanceService.daysByYear(LocalDate.of(Y, 12, 30), LocalDate.of(Y + 1, 1, 2), new BigDecimal("4"));
        assertDays("2", split.get(Y));
        assertDays("2", split.get(Y + 1));
        assertDays("0.5", LeaveBalanceService.daysByYear(LocalDate.of(Y, 5, 1), LocalDate.of(Y, 5, 1), new BigDecimal("0.5")).get(Y));
    }

    // ── entitlements ──

    @Test
    void regularPaidBalance_isCreatedFromTheConfiguredPolicy_15Days() {
        LeaveBalance b = service.ensureBalance(emp, annual, Y);
        assertDays("15", b.getTotalDays());
        verify(balanceRepo).insertIfAbsent(emp, annual.getId(), Y, new BigDecimal("15"));
    }

    @Test
    void policyChange_appliesTheValueConfiguredForThatYear_notAHardcodedOne() {
        when(policyRepo.findFirstByLeaveTypeIdAndEffectiveYearLessThanEqualOrderByEffectiveYearDesc(annual.getId(), Y))
                .thenReturn(Optional.of(LeaveEntitlementPolicy.builder().days(new BigDecimal("18")).build()));
        assertDays("18", service.ensureBalance(emp, annual, Y).getTotalDays());
    }

    @Test
    void unpaidLeave_isUnlimited_neverReadsOrWritesABalance() {
        assertTrue(service.entitlementDays(unpaid, Y).isEmpty());
        service.requireAvailable(emp, unpaid, LocalDate.of(Y, 3, 1), LocalDate.of(Y, 6, 30), new BigDecimal("122"), null);
        service.consume(emp, unpaid, LocalDate.of(Y, 3, 1), LocalDate.of(Y, 6, 30), new BigDecimal("122"));
        verifyNoInteractions(balanceRepo, occurrenceRepo);
    }

    @Test
    void paidTypesKeepIndependentBalances_sickDeductsFromSickOnly() {
        LeaveBalance a = put(annual, Y, "15", "0");
        LeaveBalance s = put(sick, Y, "15", "0");
        service.consume(emp, sick, LocalDate.of(Y, 4, 6), LocalDate.of(Y, 4, 8), new BigDecimal("3"));
        assertDays("3", s.getUsedDays());
        assertDays("0", a.getUsedDays());
    }

    @Test
    void exhaustedSickBalance_blocksSick_evenWithAnnualDaysLeft() {
        put(annual, Y, "15", "0");
        put(sick, Y, "15", "15");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.requireAvailable(emp, sick, LocalDate.of(Y, 4, 6), LocalDate.of(Y, 4, 6), BigDecimal.ONE, null));
        assertEquals("Leave request exceeds your available Sick Leave balance of 0 days.", ex.getMessage());
    }

    // ── pending reservations ──

    @Test
    void pendingRequestsReserveDays_andAnEditedRequestDoesNotReserveAgainstItself() {
        put(annual, Y, "15", "5");
        LeaveRequest other = pending(annual, LocalDate.of(Y, 2, 2), LocalDate.of(Y, 2, 9), "8");
        when(requestRepo.findPendingOverlapping(eq(emp), eq(annual.getId()), any(), any())).thenReturn(List.of(other));

        assertThrows(IllegalArgumentException.class,
                () -> service.requireAvailable(emp, annual, LocalDate.of(Y, 5, 4), LocalDate.of(Y, 5, 6), new BigDecimal("3"), null));
        service.requireAvailable(emp, annual, LocalDate.of(Y, 5, 4), LocalDate.of(Y, 5, 5), new BigDecimal("2"), null);
        // Editing `other` itself: its own 8 days don't count against it.
        service.requireAvailable(emp, annual, LocalDate.of(Y, 2, 2), LocalDate.of(Y, 2, 11), new BigDecimal("10"), other.getId());
    }

    @Test
    void crossYearRequest_isCheckedAndDeductedPerLeaveYear() {
        LeaveBalance thisYear = put(annual, Y, "15", "14");
        LeaveBalance nextYear = put(annual, Y + 1, "15", "0");
        LocalDate start = LocalDate.of(Y, 12, 30);
        LocalDate end = LocalDate.of(Y + 1, 1, 1);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.requireAvailable(emp, annual, start, end, new BigDecimal("3"), null));
        assertTrue(ex.getMessage().contains("for " + Y), ex.getMessage());

        thisYear.setUsedDays(new BigDecimal("13"));
        service.requireAvailable(emp, annual, start, end, new BigDecimal("3"), null);
        service.consume(emp, annual, start, end, new BigDecimal("3"));
        assertDays("15", thisYear.getUsedDays());
        assertDays("1", nextYear.getUsedDays());
    }

    // ── carried-forward bucket ──

    @Test
    void carriedForwardIsConsumedFirst_andOnlyForDatesOnOrBeforeExpiry() {
        LeaveBalance b = put(annual, Y, "15", "0");
        b.setCarriedForwardDays(new BigDecimal("5"));
        b.setCarriedForwardExpiresOn(LocalDate.of(Y, 3, 31));

        // 4 days before expiry -> all from carried-forward
        service.consume(emp, annual, LocalDate.of(Y, 3, 2), LocalDate.of(Y, 3, 5), new BigDecimal("4"));
        assertDays("4", b.getCarriedForwardUsedDays());
        assertDays("0", b.getUsedDays());

        // 3 days straddling expiry (Mar 30, 31, Apr 1): 1 CF left covers one eligible date, the rest is current
        service.consume(emp, annual, LocalDate.of(Y, 3, 30), LocalDate.of(Y, 4, 1), new BigDecimal("3"));
        assertDays("5", b.getCarriedForwardUsedDays());
        assertDays("2", b.getUsedDays());
    }

    @Test
    void currentFirstPriority_usesCurrentYearBeforeCarriedForward() {
        LeaveType currentFirst = paidType("ANNUAL2", "Annual");
        currentFirst.setCarryForwardPriority(LeaveType.CURRENT_FIRST);
        LeaveBalance b = LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(emp).leaveType(currentFirst).year(Y)
                .totalDays(new BigDecimal("2")).usedDays(BigDecimal.ZERO).carriedForwardDays(new BigDecimal("5"))
                .carriedForwardExpiresOn(LocalDate.of(Y, 3, 31)).build();
        table.put(currentFirst.getId() + ":" + Y, b);

        service.consume(emp, currentFirst, LocalDate.of(Y, 3, 2), LocalDate.of(Y, 3, 4), new BigDecimal("3"));
        assertDays("2", b.getUsedDays());
        assertDays("1", b.getCarriedForwardUsedDays());
    }

    @Test
    void expiredCarriedForwardCannotCoverLeaveAfterExpiry() {
        LeaveBalance b = put(annual, Y, "1", "0");
        b.setCarriedForwardDays(new BigDecimal("5"));
        b.setCarriedForwardExpiresOn(LocalDate.of(Y, 3, 31));
        assertThrows(IllegalArgumentException.class,
                () -> service.requireAvailable(emp, annual, LocalDate.of(Y, 4, 6), LocalDate.of(Y, 4, 7), new BigDecimal("2"), null));
        assertThrows(IllegalStateException.class,
                () -> service.consume(emp, annual, LocalDate.of(Y, 4, 6), LocalDate.of(Y, 4, 7), new BigDecimal("2")));
    }

    @Test
    void balanceResponse_reportsEntitlementCarriedForwardUsedPendingAndRemaining() {
        LeaveBalance b = put(annual, Y, "15", "3");
        b.setCarriedForwardDays(new BigDecimal("5"));
        b.setCarriedForwardUsedDays(new BigDecimal("1"));
        b.setCarriedForwardExpiresOn(LocalDate.of(Y, 3, 31));
        when(requestRepo.findPendingOverlapping(eq(emp), eq(annual.getId()), any(), any()))
                .thenReturn(List.of(pending(annual, LocalDate.of(Y, 6, 1), LocalDate.of(Y, 6, 2), "2")));

        LeaveBalanceResponse r = service.toResponse(b);
        assertDays("15", r.getTotalDays());
        assertDays("4", r.getUsedDays());
        assertDays("2", r.getPendingDays());
        assertDays("4", r.getCarriedForwardRemainingDays());
        assertDays("14", r.getRemainingDays()); // 12 current + 4 CF - 2 pending
        assertEquals(LocalDate.of(Y, 3, 31), r.getCarriedForwardExpiresOn());
    }

    // ── maternity (per occurrence) ──

    @Test
    void maternity_firstApprovalOpensAn182DayOccurrence_andLaterRequestsShareIt() {
        LocalDate start = LocalDate.of(Y, 1, 5);
        service.consume(emp, maternity, start, start.plusDays(99), new BigDecimal("100"));
        ArgumentCaptor<LeaveOccurrence> saved = ArgumentCaptor.forClass(LeaveOccurrence.class);
        verify(occurrenceRepo, atLeastOnce()).save(saved.capture());
        LeaveOccurrence occ = saved.getValue();
        assertDays("182", occ.getEntitlementDays());
        assertDays("100", occ.getUsedDays());
        assertEquals(start.plusMonths(12).minusDays(1), occ.getWindowEndDate());

        // A second request for the same occurrence draws from the SAME 182 days — no fresh entitlement.
        when(occurrenceRepo.findCovering(eq(emp), eq(maternity.getId()), any())).thenReturn(Optional.of(occ));
        LocalDate later = start.plusDays(100);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.requireAvailable(emp, maternity, later, later.plusDays(89), new BigDecimal("90"), null));
        assertTrue(ex.getMessage().contains("82"), ex.getMessage());
        service.requireAvailable(emp, maternity, later, later.plusDays(81), new BigDecimal("82"), null);
        assertThrows(IllegalStateException.class, () -> service.consume(emp, maternity, later, later.plusDays(89), new BigDecimal("90")));
        verifyNoInteractions(balanceRepo); // never a yearly balance
    }

    @Test
    void maternity_pendingRequestsReserveTheProspectiveOccurrence() {
        LocalDate start = LocalDate.of(Y, 1, 5);
        when(requestRepo.findPendingOverlapping(eq(emp), eq(maternity.getId()), any(), any()))
                .thenReturn(List.of(pending(maternity, start, start.plusDays(149), "150")));
        assertThrows(IllegalArgumentException.class,
                () -> service.requireAvailable(emp, maternity, start.plusDays(150), start.plusDays(199), new BigDecimal("50"), null));
    }

    // ── attendance penalties ──

    @Test
    void penaltyDebit_takesCarriedForwardFirst_isCappedAtWhatIsLeft_andCreditGoesBack() {
        LeaveBalance b = put(annual, Y, "15", "14");
        b.setCarriedForwardDays(new BigDecimal("1"));
        b.setCarriedForwardExpiresOn(LocalDate.of(Y, 3, 31));

        assertDays("2", service.debitUpTo(emp, annual, LocalDate.of(Y, 3, 10), new BigDecimal("5")));
        assertDays("1", b.getCarriedForwardUsedDays());
        assertDays("15", b.getUsedDays());

        assertTrue(service.credit(emp, annual, Y, new BigDecimal("2")));
        assertDays("0", b.getCarriedForwardUsedDays());
        assertDays("14", b.getUsedDays());
    }

    @Test
    void penaltyDebit_neverTouchesUnpaidOrMaternity() {
        assertDays("0", service.debitUpTo(emp, unpaid, LocalDate.of(Y, 3, 10), BigDecimal.ONE));
        assertDays("0", service.debitUpTo(emp, maternity, LocalDate.of(Y, 3, 10), BigDecimal.ONE));
        verifyNoInteractions(balanceRepo);
    }

    @Test
    void newHireAndNewType_getBalancesForEveryActiveRegularPaidTypeOnly() {
        when(typeRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(annual, sick, unpaid, maternity));
        service.ensureBalances(emp, Y);
        verify(balanceRepo).insertIfAbsent(emp, annual.getId(), Y, new BigDecimal("15"));
        verify(balanceRepo).insertIfAbsent(emp, sick.getId(), Y, new BigDecimal("15"));
        verify(balanceRepo, times(2)).insertIfAbsent(any(), any(), anyInt(), any());
    }
}
