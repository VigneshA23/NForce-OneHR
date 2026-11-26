package com.nforce.onehr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.LeaveEmployeeProfile;
import com.nforce.onehr.entity.LeaveAccrualCredit;
import com.nforce.onehr.entity.LeaveBalance;
import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeaveType;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LeaveAccrualCreditRepository;
import com.nforce.onehr.repository.LeaveBalanceRepository;
import com.nforce.onehr.repository.LeaveEntitlementPolicyRepository;
import com.nforce.onehr.repository.LeaveTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V314 crediting: the monthly schedule, YEARLY vs MONTHLY, joiners/leavers, idempotency, re-crediting. */
@ExtendWith(MockitoExtension.class)
class LeaveAccrualServiceTest {

    private static final String M = LeaveEntitlementPolicy.MONTHLY;
    private static final String Y = LeaveEntitlementPolicy.YEARLY;

    @Mock private LeaveTypeRepository typeRepo;
    @Mock private LeaveBalanceRepository balanceRepo;
    @Mock private LeaveEntitlementPolicyRepository policyRepo;
    @Mock private LeaveAccrualCreditRepository creditRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private LeaveEligibilityService eligibility;
    @Mock private AuditService auditService;
    @Mock private LeaveJobMonitor jobMonitor;
    @Mock private PlatformTransactionManager txManager;

    private LeaveAccrualService service;
    private final UUID emp = UUID.randomUUID();
    private final LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave")
            .systemKey(LeaveType.SYSTEM_PAID).build();
    /** Stands in for leave_accrual_credits. */
    private final List<LeaveAccrualCredit> ledger = new ArrayList<>();
    private LeaveBalance balance;

    @BeforeEach
    void setUp() {
        service = new LeaveAccrualService(typeRepo, balanceRepo, policyRepo, creditRepo, employeeRepo, eligibility,
                new AttendanceProperties(), txManager, jobMonitor);
        lenient().when(eligibility.rulesFor(any())).thenReturn(LeaveEligibilityService.Rules.EVERYONE);
        lenient().when(creditRepo.findByEmployeeUserIdAndLeaveTypeIdAndYearOrderByMonthAsc(any(), any(), any()))
                .thenAnswer(inv -> ledger.stream().filter(c -> c.getYear().equals(inv.getArgument(2))).toList());
        lenient().when(creditRepo.insertIfAbsent(any(), any(), any(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
            int year = inv.getArgument(3), month = inv.getArgument(4);
            if (ledger.stream().anyMatch(c -> c.getYear() == year && c.getMonth() == month)) return 0;
            ledger.add(LeaveAccrualCredit.builder().year(year).month(month).days(inv.getArgument(5))
                    .source(LeaveAccrualCredit.SOURCE_ACCRUAL).build());
            return 1;
        });
        lenient().when(balanceRepo.findByEmployeeUserIdAndLeaveTypeIdAndYear(any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(balance));
        lenient().when(balanceRepo.insertIfAbsent(any(), any(), anyInt(), any())).thenAnswer(inv -> {
            if (balance == null) {
                balance = LeaveBalance.builder().employeeUserId(emp).leaveType(paid).year(inv.getArgument(2))
                        .totalDays(BigDecimal.ZERO).usedDays(BigDecimal.ZERO).build();
                return 1;
            }
            return 0;
        });
    }

    private void employee(LocalDate joining, LocalDate lastWorkingDay, boolean active) {
        when(employeeRepo.findLeaveProfile(emp)).thenReturn(Optional.of(new LeaveEmployeeProfile(emp, "Female", null, null,
                "FULL_TIME", joining, lastWorkingDay, active, null)));
    }

    private void policy(String days, String frequency) {
        lenient().when(policyRepo.findFirstByLeaveTypeIdAndEffectiveYearLessThanEqualOrderByEffectiveYearDesc(eq(paid.getId()), any()))
                .thenReturn(Optional.of(LeaveEntitlementPolicy.builder().effectiveYear(2000).days(new BigDecimal(days))
                        .creditFrequency(frequency).build()));
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static BigDecimal sum(Map<Integer, BigDecimal> schedule) {
        return schedule.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── schedule ──

    @Test
    void monthlyShares_alwaysAddUpToTheQuotaExactly() {
        for (String q : List.of("15", "12", "10", "7", "18.5")) {
            Map<Integer, BigDecimal> s = LeaveAccrualService.schedule(new BigDecimal(q), M, LocalDate.of(2020, 1, 1), null, 2027);
            assertEquals(12, s.size());
            assertDays(q, sum(s));
        }
        assertDays("1.25", LeaveAccrualService.monthShare(new BigDecimal("15"), 7));
        assertDays("1", LeaveAccrualService.monthShare(new BigDecimal("12"), 3));
        assertDays("0.83", LeaveAccrualService.monthShare(new BigDecimal("10"), 1));
        assertDays("0.84", LeaveAccrualService.monthShare(new BigDecimal("10"), 2));
    }

    @Test
    void monthlyJoiner_getsTheWholeJoiningMonth_noProRating() {
        Map<Integer, BigDecimal> s = LeaveAccrualService.schedule(new BigDecimal("12"), M, LocalDate.of(2027, 10, 28), null, 2027);
        assertEquals(Set.of(10, 11, 12), s.keySet());
        assertDays("3", sum(s));
    }

    @Test
    void yearlyJoiner_getsTheMonthsLeft_roundedToHalfADay() {
        // 15 x 3/12 = 3.75 -> 4
        assertDays("4", sum(LeaveAccrualService.schedule(new BigDecimal("15"), Y, LocalDate.of(2027, 10, 5), null, 2027)));
        // 15 x 5/12 = 6.25 -> 6.5 (half up)
        assertDays("6.5", sum(LeaveAccrualService.schedule(new BigDecimal("15"), Y, LocalDate.of(2027, 8, 20), null, 2027)));
        // a full year is never rounded
        assertDays("15", sum(LeaveAccrualService.schedule(new BigDecimal("15"), Y, LocalDate.of(2019, 8, 20), null, 2027)));
    }

    @Test
    void creditsStopAfterTheLastWorkingMonth_andNothingOutsideEmployment() {
        Map<Integer, BigDecimal> s = LeaveAccrualService.schedule(new BigDecimal("12"), M, LocalDate.of(2020, 1, 1),
                LocalDate.of(2027, 4, 10), 2027);
        assertEquals(Set.of(1, 2, 3, 4), s.keySet());
        assertTrue(LeaveAccrualService.schedule(new BigDecimal("12"), M, LocalDate.of(2028, 1, 1), null, 2027).isEmpty());
        assertTrue(LeaveAccrualService.schedule(new BigDecimal("12"), M, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31), 2027).isEmpty());
    }

    @Test
    void dueMonths_monthlyOnTheFirst_yearlyAllAtOnce() {
        Map<Integer, BigDecimal> s = LeaveAccrualService.schedule(new BigDecimal("12"), M, LocalDate.of(2020, 1, 1), null, 2027);
        assertEquals(Set.of(1, 2, 3), LeaveAccrualService.dueMonths(s, M, 2027, LocalDate.of(2027, 3, 1)));
        assertEquals(Set.of(1, 2), LeaveAccrualService.dueMonths(s, M, 2027, LocalDate.of(2027, 2, 28)));
        assertEquals(12, LeaveAccrualService.dueMonths(s, Y, 2027, LocalDate.of(2027, 1, 1)).size());
        assertTrue(LeaveAccrualService.dueMonths(s, Y, 2027, LocalDate.of(2026, 12, 31)).isEmpty());
    }

    // ── accrue ──

    @Test
    void accrue_creditsEveryDueMonth_once_evenWhenRunAgain() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        policy("15", M);
        // 2025 has fully passed: all 12 months are due.
        assertDays("15", service.accrue(emp, paid, 2025));
        assertDays("15", balance.getTotalDays());
        assertEquals(12, ledger.size());
        assertDays("0", service.accrue(emp, paid, 2025));
        assertDays("15", balance.getTotalDays());
    }

    @Test
    void accrue_aFutureYear_creditsNothingYet_butProjectsTheWholeQuota() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        policy("12", M);
        assertDays("0", service.accrue(emp, paid, 2031));
        LeaveAccrualService.Projection p = service.project(emp, paid, 2031);
        assertDays("12", p.upcomingDays());
        assertEquals(LocalDate.of(2031, 1, 1), p.nextCreditDate());
        assertDays("1", p.nextCreditDays());
    }

    @Test
    void accrue_keepsManualAdjustments_byOnlyAddingNewCredits() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        policy("12", M);
        balance = LeaveBalance.builder().employeeUserId(emp).leaveType(paid).year(2025)
                .totalDays(new BigDecimal("7")).usedDays(BigDecimal.ZERO).build(); // e.g. HR set it to 7 after 6 credits
        for (int m = 1; m <= 6; m++) {
            ledger.add(LeaveAccrualCredit.builder().year(2025).month(m).days(BigDecimal.ONE).source("ACCRUAL").build());
        }
        assertDays("6", service.accrue(emp, paid, 2025));
        assertDays("13", balance.getTotalDays());
    }

    @Test
    void migratedBalances_areNeverCreditedAgain() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        policy("15", M);
        balance = LeaveBalance.builder().employeeUserId(emp).leaveType(paid).year(2025)
                .totalDays(new BigDecimal("45")).usedDays(BigDecimal.ZERO).build();
        for (int m = 1; m <= 12; m++) {
            ledger.add(LeaveAccrualCredit.builder().year(2025).month(m).days(BigDecimal.ZERO).source("MIGRATION").build());
        }
        assertDays("0", service.accrue(emp, paid, 2025));
        assertDays("45", balance.getTotalDays());
    }

    @Test
    void accrue_skipsInactiveEmployees_ineligibleEmployees_andInactiveTypes() {
        employee(LocalDate.of(2020, 1, 1), null, false);
        policy("12", M);
        assertDays("0", service.accrue(emp, paid, 2025));

        employee(LocalDate.of(2020, 1, 1), null, true);
        when(eligibility.rulesFor(paid.getId())).thenReturn(new LeaveEligibilityService.Rules(
                Set.of("male"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()));
        assertDays("0", service.accrue(emp, paid, 2025)); // the profile is Female

        LeaveType inactive = LeaveType.builder().id(UUID.randomUUID()).code("OLD").name("Old").active(false).build();
        assertDays("0", service.accrue(emp, inactive, 2025));
        verify(creditRepo, never()).insertIfAbsent(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void switchingMonthlyToYearly_creditsTheRestAtOnce_andBackNeverDoubles() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        policy("12", M);
        // Three months credited monthly, then HR switches the year to all-at-once.
        balance = LeaveBalance.builder().employeeUserId(emp).leaveType(paid).year(2025)
                .totalDays(new BigDecimal("3")).usedDays(BigDecimal.ZERO).build();
        for (int m = 1; m <= 3; m++) {
            ledger.add(LeaveAccrualCredit.builder().year(2025).month(m).days(BigDecimal.ONE).source("ACCRUAL").build());
        }
        policy("12", Y);
        assertDays("9", service.accrue(emp, paid, 2025));
        assertDays("12", balance.getTotalDays());
        policy("12", M);
        assertDays("0", service.accrue(emp, paid, 2025));
        assertDays("12", balance.getTotalDays());
    }

    // ── re-crediting after an entitlement change ──

    @Test
    void recreditExisting_addsTheDifferenceForCreditedMonths_orResetsAMigratedBalance() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        policy("24", M); // was 12 a year: 1 per month, now 2
        balance = LeaveBalance.builder().employeeUserId(emp).leaveType(paid).year(2027)
                .totalDays(new BigDecimal("4.5")).usedDays(BigDecimal.ZERO).build(); // 3 credits + a 1.5 manual top-up
        for (int m = 1; m <= 3; m++) {
            ledger.add(LeaveAccrualCredit.builder().year(2027).month(m).days(BigDecimal.ONE).source("ACCRUAL").build());
        }
        when(balanceRepo.findByLeaveTypeIdAndYearGreaterThanEqual(paid.getId(), 2027)).thenReturn(List.of(balance));
        assertEquals(1, service.recreditExisting(paid, 2027, Integer.MAX_VALUE));
        assertDays("7.5", balance.getTotalDays());
        ledger.forEach(c -> assertDays("2", c.getDays()));

        // A migrated (pre-crediting) balance has no per-month figures: its credited total becomes the new shares.
        ledger.clear();
        balance.setTotalDays(new BigDecimal("45"));
        for (int m = 1; m <= 12; m++) {
            ledger.add(LeaveAccrualCredit.builder().year(2027).month(m).days(BigDecimal.ZERO).source("MIGRATION").build());
        }
        service.recreditExisting(paid, 2027, Integer.MAX_VALUE);
        assertDays("24", balance.getTotalDays());
        assertTrue(ledger.stream().allMatch(c -> "ACCRUAL".equals(c.getSource())));
    }
}
