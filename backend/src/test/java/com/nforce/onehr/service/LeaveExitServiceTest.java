package com.nforce.onehr.service;

import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveExitSettlement;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveExitSettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** LV-04: a leaver's leave position — encashable days, or leave taken beyond the entitlement to recover. */
@ExtendWith(MockitoExtension.class)
class LeaveExitServiceTest {

    @Mock private EmployeeRepository employeeRepo;
    @Mock private LeaveBalanceRepository balanceRepo;
    @Mock private LeaveExitSettlementRepository settlementRepo;
    @Mock private LeaveAccrualService accrual;
    @Mock private PlatformTransactionManager txManager;

    private LeaveExitService service;
    private final UUID emp = UUID.randomUUID();
    private final LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave")
            .classification("PAID").entitlementBasis(LeaveType.BASIS_ANNUAL).build();
    private final LeaveType unpaid = LeaveType.builder().id(UUID.randomUUID()).code("UNPAID").name("Unpaid Leave").classification("UNPAID").build();

    @BeforeEach
    void setUp() {
        service = new LeaveExitService(employeeRepo, balanceRepo, settlementRepo, accrual, txManager);
        lenient().when(settlementRepo.saveAll(any())).thenAnswer(inv -> new java.util.ArrayList<>((java.util.Collection<LeaveExitSettlement>) inv.getArgument(0)));
    }

    private void leaving(LocalDate lastWorkingDay) {
        when(employeeRepo.findLeaveProfile(emp)).thenReturn(Optional.of(new LeaveEmployeeProfile(emp, "Female", null, null,
                "FULL_TIME", LocalDate.of(2020, 1, 1), lastWorkingDay, true, null)));
    }

    private LeaveBalance balance(LeaveType type, String total, String used, String carried) {
        return LeaveBalance.builder().employeeUserId(emp).leaveType(type).year(2026).totalDays(new BigDecimal(total))
                .usedDays(new BigDecimal(used)).carriedForwardDays(new BigDecimal(carried)).carriedForwardUsedDays(BigDecimal.ZERO)
                .carriedForwardExpiredDays(BigDecimal.ZERO).build();
    }

    @Test
    void recomputesUpToTheLastWorkingDay_thenFlagsLeaveTakenBeyondIt() {
        leaving(LocalDate.of(2026, 3, 10));
        // After the recompute the year's Paid Leave is 4 days (Jan-Mar); 10 were taken.
        when(balanceRepo.findByEmployeeUserIdAndYear(emp, 2026)).thenReturn(List.of(balance(paid, "4", "10", "1"), balance(unpaid, "0", "0", "0")));

        List<LeaveExitSettlement> out = service.settle(emp);

        verify(accrual).reconcile(List.of(emp), LocalDate.of(2026, 1, 1), false);
        assertEquals(1, out.size(), "regular paid leave only");
        LeaveExitSettlement s = out.get(0);
        assertEquals(0, new BigDecimal("5").compareTo(s.getRecoveryDays())); // 4 + 1 carried − 10
        assertEquals(0, s.getEncashableDays().signum());
        assertEquals(LocalDate.of(2026, 3, 10), s.getLastWorkingDay());
    }

    @Test
    void unusedDaysCanBeEncashed() {
        leaving(LocalDate.of(2026, 9, 30));
        when(balanceRepo.findByEmployeeUserIdAndYear(emp, 2026)).thenReturn(List.of(balance(paid, "11.5", "4", "0")));
        assertEquals(0, new BigDecimal("7.5").compareTo(service.encashableDays(emp)));
    }

    @Test
    void aWithdrawnExit_removesTheSettlement_andRestoresTheCredits() {
        leaving(null);
        LeaveExitSettlement old = LeaveExitSettlement.builder().employeeUserId(emp).leaveTypeId(paid.getId()).year(2026).build();
        when(settlementRepo.findByEmployeeUserId(emp)).thenReturn(List.of(old));
        assertTrue(service.settle(emp).isEmpty());
        verify(settlementRepo).deleteAll(List.of(old));
        // The withdrawn exit's year is re-credited (months taken back are restored).
        verify(accrual).reconcile(List.of(emp), LocalDate.of(2026, 1, 1), false);
    }
}
