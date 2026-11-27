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
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        return new LeaveBalanceService(types, balances, mock(LeaveRequestRepository.class), plansFromPolicies(policies, types),
                mock(LeaveOccurrenceRepository.class), new AttendanceProperties(),
                yearlyAccrual(balances, policies), everyoneEligible());
    }

    static LeaveBalanceService over(LeaveTypeRepository types, LeaveBalanceRepository balances, LeaveRequestRepository requests,
                                    LeaveEntitlementPolicyRepository policies, LeaveOccurrenceRepository occurrences,
                                    AttendanceProperties attendance) {
        return new LeaveBalanceService(types, balances, requests, plansFromPolicies(policies, types), occurrences, attendance,
                yearlyAccrual(balances, policies), everyoneEligible());
    }

    /**
     * LV-01 adapter for tests written before leave plans: every employee is on one plan whose rule for a
     * type on a date is the old per-type configuration — days and frequency from the (mocked) entitlement
     * policy of that year, carry-forward from the {@link LeaveType}'s own (now unused) fields — and that
     * applies to everyone. Tests keep stubbing {@code policies} and {@code types} as before.
     */
    static LeavePlanRuleService plansFromPolicies(LeaveEntitlementPolicyRepository policies, LeaveTypeRepository types) {
        UUID planId = UUID.randomUUID();
        Answer<Optional<LeavePlanCatalog.TypeRule>> rule = inv -> {
            UUID typeId = inv.getArgument(1);
            LocalDate date = inv.getArgument(2);
            Optional<LeaveEntitlementPolicy> p = Optional.ofNullable(policies
                    .findFirstByLeaveTypeIdAndEffectiveYearLessThanEqualOrderByEffectiveYearDesc(typeId, date.getYear())).flatMap(x -> x);
            LeaveType t = types == null ? null : Optional.ofNullable(types.findById(typeId)).flatMap(x -> x).orElse(null);
            return Optional.of(new LeavePlanCatalog.TypeRule(UUID.randomUUID(), planId, null, typeId,
                    p.map(LeaveEntitlementPolicy::getDays).orElse(BigDecimal.ZERO),
                    p.map(LeaveEntitlementPolicy::getCreditFrequency).orElse(LeaveEntitlementPolicy.YEARLY),
                    t != null && t.isCarryForwardEnabled(), t != null ? t.getCarryForwardMaxDays() : null,
                    t != null ? t.getCarryForwardExpiryMonths() : null,
                    t != null && t.getCarryForwardPriority() != null ? t.getCarryForwardPriority() : LeaveType.CF_FIRST,
                    LeaveEligibilityService.Rules.EVERYONE));
        };
        LeavePlanCatalog catalog = mock(LeavePlanCatalog.class);
        lenient().when(catalog.ruleFor(any(), any(), any())).thenAnswer(rule);
        lenient().when(catalog.planOf(any(), any())).thenReturn(new LeavePlanCatalog.PlanResolution(planId, LeavePlanCatalog.SOURCE_DEFAULT, null));
        LeavePlanRuleService service = mock(LeavePlanRuleService.class);
        lenient().when(service.catalog(any())).thenReturn(catalog);
        lenient().when(service.catalogForAll()).thenReturn(catalog);
        lenient().when(service.ruleFor(any(), any(), any())).thenAnswer(rule);
        return service;
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
        lenient().when(eligibility.isEligible(any(), any(), any())).thenReturn(true);
        lenient().when(eligibility.eligibleTypes(any(), any())).thenAnswer(inv -> List.copyOf((Collection<LeaveType>) inv.getArgument(1)));
        // LV-06: available on any date, and every type listed as available on the apply form.
        lenient().when(eligibility.availability(any(), any(), any()))
                .thenReturn(new LeaveEligibilityService.Availability(true, true, null, null));
        lenient().when(eligibility.typesForEmployee(any(), any())).thenAnswer(inv -> {
            java.util.Map<LeaveType, LeaveEligibilityService.Availability> out = new java.util.LinkedHashMap<>();
            for (LeaveType t : (Collection<LeaveType>) inv.getArgument(1)) {
                out.put(t, new LeaveEligibilityService.Availability(true, true, null, null));
            }
            return out;
        });
        return eligibility;
    }
}
