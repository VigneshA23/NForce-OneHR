package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeaveBalanceResponse;
import com.nforce.onehr.entity.LeaveBalance;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * V314 leave in advance: a monthly-credited balance can be applied against up to the whole year's
 * quota (credited + still to come), the balance goes negative, and the "use paid before unpaid"
 * rule, the displayed balance and penalty debits only count what has been credited.
 */
@ExtendWith(MockitoExtension.class)
class LeaveAdvanceTest {

    private static final int Y = 2031;

    @Mock private LeaveTypeRepository typeRepo;
    @Mock private LeaveBalanceRepository balanceRepo;
    @Mock private LeaveRequestRepository requestRepo;
    @Mock private LeaveEntitlementPolicyRepository policyRepo;
    @Mock private LeaveOccurrenceRepository occurrenceRepo;
    @Mock private LeaveAccrualService accrual;
    @Mock private LeaveEligibilityService eligibility;

    private LeaveBalanceService service;
    private final UUID emp = UUID.randomUUID();
    private final LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave")
            .systemKey(LeaveType.SYSTEM_PAID).build();
    private final LeaveType unpaid = LeaveType.builder().id(UUID.randomUUID()).code("UNPAID").name("Unpaid Leave")
            .classification(LeaveTypeClassification.UNPAID).build();
    private LeaveBalance balance;

    @BeforeEach
    void setUp() {
        service = new LeaveBalanceService(typeRepo, balanceRepo, requestRepo, policyRepo, occurrenceRepo, new AttendanceProperties(),
                accrual, eligibility);
        // 2 days credited so far (Jan, Feb of a 12-day monthly quota), 10 still to come.
        balance = LeaveBalance.builder().id(UUID.randomUUID()).employeeUserId(emp).leaveType(paid).year(Y)
                .totalDays(new BigDecimal("2")).usedDays(BigDecimal.ZERO).build();
        lenient().when(balanceRepo.findByEmployeeUserIdAndLeaveTypeIdAndYear(emp, paid.getId(), Y)).thenReturn(Optional.of(balance));
        lenient().when(accrual.project(emp, paid, Y)).thenAnswer(inv -> new LeaveAccrualService.Projection("MONTHLY",
                new BigDecimal("10"), LocalDate.of(Y, 3, 1), BigDecimal.ONE));
        lenient().when(eligibility.isEligible(any(), any())).thenReturn(true);
        lenient().when(eligibility.eligibleTypes(any(), any())).thenAnswer(inv -> List.copyOf(inv.<java.util.Collection<LeaveType>>getArgument(1)));
        lenient().when(typeRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(paid, unpaid));
    }

    @Test
    void canApplyBeyondWhatIsCredited_upToTheYearsQuota_butNotBeyond() {
        LocalDate from = LocalDate.of(Y, 2, 10);
        service.requireAvailable(emp, paid, from, from.plusDays(4), new BigDecimal("5"), null);
        service.requireAvailable(emp, paid, from, from.plusDays(11), new BigDecimal("12"), null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.requireAvailable(emp, paid, from, from.plusDays(12), new BigDecimal("13"), null));
        assertTrue(e.getMessage().contains("12"), e.getMessage());
    }

    @Test
    void approvingAdvanceLeave_makesTheBalanceNegative_andShowsBothFigures() {
        when(balanceRepo.save(any(LeaveBalance.class))).thenAnswer(inv -> inv.getArgument(0));
        service.consume(emp, paid, LocalDate.of(Y, 2, 10), LocalDate.of(Y, 2, 14), new BigDecimal("5"));
        assertEquals(0, new BigDecimal("5").compareTo(balance.getUsedDays()));

        LeaveBalanceResponse r = service.toResponse(balance);
        assertEquals(0, new BigDecimal("-3").compareTo(r.getRemainingDays()));
        assertEquals(0, new BigDecimal("7").compareTo(r.getAvailableToApplyDays()));
        assertEquals(0, new BigDecimal("12").compareTo(r.getAnnualQuotaDays()));
        assertEquals("MONTHLY", r.getCreditFrequency());
        assertEquals(LocalDate.of(Y, 3, 1), r.getNextCreditDate());
    }

    @Test
    void approvalBeyondTheYearsQuota_isRefused() {
        assertThrows(IllegalStateException.class,
                () -> service.consume(emp, paid, LocalDate.of(Y, 2, 10), LocalDate.of(Y, 2, 22), new BigDecimal("13")));
        verify(balanceRepo, never()).save(any());
    }

    @Test
    void paidBeforeUnpaid_andPenaltyDebits_countCreditedDaysOnly() {
        balance.setUsedDays(new BigDecimal("2")); // everything credited is used; 10 still to come
        assertEquals(0, BigDecimal.ZERO.compareTo(service.availablePaidDays(emp, Y, null)));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.debitUpTo(emp, paid, LocalDate.of(Y, 2, 20), BigDecimal.ONE)));
    }

    @Test
    void penaltyDebit_skipsATypeTheEmployeeIsNotEligibleFor() {
        when(eligibility.isEligible(emp, paid)).thenReturn(false);
        assertEquals(0, BigDecimal.ZERO.compareTo(service.debitUpTo(emp, paid, LocalDate.of(Y, 2, 20), BigDecimal.ONE)));
        verify(balanceRepo, never()).save(any());
    }

    @Test
    void ineligibleTypes_getNoBalanceRowAndNoCard() {
        when(typeRepo.findAllByOrderByNameAsc()).thenReturn(List.of(paid, unpaid));
        when(balanceRepo.findByEmployeeUserIdAndYear(emp, Y)).thenReturn(List.of());
        doReturn(List.of(unpaid)).when(eligibility).eligibleTypes(any(), any());
        List<LeaveBalanceResponse> cards = service.balancesFor(emp, Y);
        assertEquals(1, cards.size());
        assertEquals("UNPAID", cards.get(0).getLeaveTypeCode());
        verify(balanceRepo, never()).insertIfAbsent(any(), any(), anyInt(), any());
    }
}
