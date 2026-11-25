package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveEntitlementPolicyRepository;
import com.nforce.onehr.repository.LeaveOccurrenceRepository;
import com.nforce.onehr.repository.LeaveRequestRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** Leave-balance test wiring shared by the leave service tests. */
final class TestLeaveBalances {

    private TestLeaveBalances() {}

    /** A real {@link LeaveBalanceService} over the caller's mocked type/balance repositories (no pending requests, no policies). */
    static LeaveBalanceService over(LeaveTypeRepository types, LeaveBalanceRepository balances) {
        LeaveEntitlementPolicyRepository policies = mock(LeaveEntitlementPolicyRepository.class);
        return new LeaveBalanceService(types, balances, mock(LeaveRequestRepository.class), policies,
                mock(LeaveOccurrenceRepository.class), new AttendanceProperties(),
                yearlyAccrual(balances, policies), everyoneEligible());
    }

    static LeaveBalanceService over(LeaveTypeRepository types, LeaveBalanceRepository balances, LeaveRequestRepository requests,
                                    LeaveEntitlementPolicyRepository policies, LeaveOccurrenceRepository occurrences,
                                    AttendanceProperties attendance) {
        return new LeaveBalanceService(types, balances, requests, policies, occurrences, attendance,
                yearlyAccrual(balances, policies), everyoneEligible());
    }

    /**
     * Crediting as it behaved before V314, for tests about everything else: a new (0-credited) balance
     * row receives the whole policy entitlement the first time it is brought up to date, and there
     * are never credits still to come (so no leave in advance).
     */
    static LeaveAccrualService yearlyAccrual(LeaveBalanceRepository balances, LeaveEntitlementPolicyRepository policies) {
        LeaveAccrualService accrual = mock(LeaveAccrualService.class);
        Set<String> credited = new HashSet<>();
        lenient().when(accrual.accrue(any(), any(), anyInt())).thenAnswer(inv -> {
            UUID emp = inv.getArgument(0);
            LeaveType type = inv.getArgument(1);
            int year = inv.getArgument(2);
            if (!type.isAnnualBalance() || !credited.add(emp + ":" + type.getId() + ":" + year)) return BigDecimal.ZERO;
            Optional<LeaveBalance> row = balances.findByEmployeeUserIdAndLeaveTypeIdAndYear(emp, type.getId(), year);
            if (row.isEmpty() || row.get().getTotalDays().signum() != 0) return BigDecimal.ZERO;
            BigDecimal days = Optional.ofNullable(policies
                            .findFirstByLeaveTypeIdAndEffectiveYearLessThanEqualOrderByEffectiveYearDesc(type.getId(), year))
                    .flatMap(p -> p).map(LeaveEntitlementPolicy::getDays).orElse(BigDecimal.ZERO);
            row.get().setTotalDays(days);
            return days;
        });
        lenient().when(accrual.project(any(), any(), anyInt())).thenReturn(LeaveAccrualService.Projection.NONE);
        return accrual;
    }

    /** No "applies to" rules anywhere: every employee is eligible for every type. */
    @SuppressWarnings("unchecked")
    static LeaveEligibilityService everyoneEligible() {
        LeaveEligibilityService eligibility = mock(LeaveEligibilityService.class);
        lenient().when(eligibility.isEligible(any(), any())).thenReturn(true);
        lenient().when(eligibility.rulesFor(any())).thenReturn(LeaveEligibilityService.Rules.EVERYONE);
        lenient().when(eligibility.eligibleTypes(any(), any())).thenAnswer(inv -> List.copyOf((Collection<LeaveType>) inv.getArgument(1)));
        return eligibility;
    }
}
