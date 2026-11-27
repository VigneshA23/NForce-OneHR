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
    @Mock private LeaveAccrualCreditRepository creditRepo;
    @Mock private EmployeeRepository employeeRepo;
    @Mock private AuditService auditService;
    @Mock private LeaveJobMonitor jobMonitor;
    @Mock private PlatformTransactionManager txManager;
    @Mock private LeaveAccrualPauseFacts pauseFacts;

    private LeaveAccrualService service;
    /** LV-01: the employee's leave plan (one org-default plan, in memory). */
    private final TestLeavePlans plans = new TestLeavePlans();
    private final UUID emp = UUID.randomUUID();
    private final LeaveType paid = LeaveType.builder().id(UUID.randomUUID()).code("PAID").name("Paid Leave")
            .systemKey(LeaveType.SYSTEM_PAID).build();
    /** Stands in for leave_accrual_credits. */
    private final List<LeaveAccrualCredit> ledger = new ArrayList<>();
    private LeaveBalance balance;

    @BeforeEach
    void setUp() {
        service = new LeaveAccrualService(typeRepo, balanceRepo, creditRepo, employeeRepo, plans.service(),
                new AttendanceProperties(), txManager, jobMonitor, pauseFacts);
        lenient().when(pauseFacts.of(any())).thenReturn(LeaveAccrualPauseFacts.Facts.NONE);
        lenient().when(creditRepo.insertPausedIfAbsent(any(), any(), any(), anyInt(), anyInt())).thenAnswer(inv -> {
            int year = inv.getArgument(3), month = inv.getArgument(4);
            if (ledger.stream().anyMatch(c -> c.getYear() == year && c.getMonth() == month)) return 0;
            ledger.add(LeaveAccrualCredit.builder().year(year).month(month).days(BigDecimal.ZERO)
                    .source(LeaveAccrualCredit.SOURCE_PAUSED).build());
            return 1;
        });
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

    /** Paid Leave in the employee's plan: {@code days} a year, credited {@code frequency}. */
    private void policy(String days, String frequency) {
        plans.with(paid, days, frequency);
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
        plans.eligibility(paid, new LeaveEligibilityService.Rules(
                Set.of("male"), Set.of(), Set.of(), Set.of(), Set.of(), Set.of()));
        assertDays("0", service.accrue(emp, paid, 2025)); // the profile is Female

        LeaveType inactive = LeaveType.builder().id(UUID.randomUUID()).code("OLD").name("Old").active(false).build();
        assertDays("0", service.accrue(emp, inactive, 2025));
        verify(creditRepo, never()).insertIfAbsent(any(), any(), any(), anyInt(), anyInt(), any());
    }

    // ── LV-06 ──

    private void profile(LocalDate joining, LocalDate probationEnd, LocalDate confirmed) {
        when(employeeRepo.findLeaveProfile(emp)).thenReturn(Optional.of(new LeaveEmployeeProfile(emp, "Female", null, null,
                "FULL_TIME", joining, null, true, null, null, null, probationEnd, confirmed)));
    }

    @Test
    void minimumTenure_creditsOnlyTheMonthsFromWhenItIsReached() {
        profile(LocalDate.of(2025, 1, 1), null, null);
        policy("12", M);
        plans.eligibility(paid, new LeaveEligibilityService.Rules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), 6, true));
        assertDays("6", service.accrue(emp, paid, 2025)); // July-December
    }

    @Test
    void probation_holdsTheCredits_andConfirmationCatchesThemUp() {
        policy("12", M);
        plans.eligibility(paid, new LeaveEligibilityService.Rules(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), null, false));
        profile(LocalDate.of(2025, 1, 1), LocalDate.of(2099, 12, 31), null); // still on probation today
        assertDays("0", service.accrue(emp, paid, 2025));
        assertTrue(ledger.isEmpty());

        profile(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30), LocalDate.of(2025, 7, 1)); // confirmed since
        assertDays("12", service.accrue(emp, paid, 2025)); // the probation months included
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

    // ── LV-03: periods, timing, pause ──

    @Test
    void quarterly_creditsEachQuarterTogether_andAddsUpToTheQuota() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        policy("10", LeaveEntitlementPolicy.QUARTERLY);
        assertDays("10", service.accrue(emp, paid, 2025));
        assertEquals(12, ledger.size(), "one ledger row per month, credited a quarter at a time");
        assertDays("0", service.accrue(emp, paid, 2025));
    }

    /** Unpaid leave days by month of 2025 for {@link #emp}. */
    private void unpaidLeave(Map<Integer, String> daysByMonth) {
        when(pauseFacts.of(emp)).thenReturn(new LeaveAccrualPauseFacts.Facts() {
            @Override public BigDecimal lopDays(LocalDate from, LocalDate to) { return BigDecimal.ZERO; }
            @Override public BigDecimal unpaidDays(LocalDate from, LocalDate to) {
                BigDecimal sum = BigDecimal.ZERO;
                for (Map.Entry<Integer, String> e : daysByMonth.entrySet()) {
                    LocalDate d = LocalDate.of(2025, e.getKey(), 1);
                    if (!d.isBefore(from.withDayOfMonth(1)) && !d.isAfter(to)) sum = sum.add(new BigDecimal(e.getValue()));
                }
                return sum;
            }
        });
    }

    @Test
    void pause_withholdsTheCreditAfterAMonthWithTooMuchUnpaidLeave_recordedAsPaused_andNeverCreditedLater() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        policy("12", M);
        plans.accrual(paid, new LeavePlanCatalog.Accrual("START", null, new BigDecimal("2")));
        unpaidLeave(Map.of(2, "3", 5, "2")); // February over the limit; May exactly at it (not over)
        // Credited at the START: March's credit depends on February.
        assertDays("11", service.accrue(emp, paid, 2025));
        LeaveAccrualCredit march = ledger.stream().filter(c -> c.getMonth() == 3).findFirst().orElseThrow();
        assertEquals(LeaveAccrualCredit.SOURCE_PAUSED, march.getSource());
        assertDays("0", march.getDays());
        assertEquals(12, ledger.size());
        assertDays("0", service.accrue(emp, paid, 2025)); // a re-run neither credits nor re-pauses
        assertEquals(12, ledger.size());

        // A later plan change re-credits the credited months but leaves the paused month withheld.
        when(balanceRepo.findByEmployeeUserIdInAndYearGreaterThanEqual(List.of(emp), 2025)).thenReturn(List.of(balance));
        policy("24", M);
        plans.accrual(paid, new LeavePlanCatalog.Accrual("START", null, new BigDecimal("2")));
        service.reconcile(List.of(emp), LocalDate.of(2025, 1, 1), false);
        assertDays("22", balance.getTotalDays());
        assertDays("0", march.getDays());
    }

    @Test
    void pause_atTheEnd_looksAtTheMonthItself() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        policy("12", M);
        plans.accrual(paid, new LeavePlanCatalog.Accrual("END", null, new BigDecimal("2")));
        unpaidLeave(Map.of(2, "3"));
        assertDays("11", service.accrue(emp, paid, 2025));
        assertEquals(LeaveAccrualCredit.SOURCE_PAUSED, ledger.stream().filter(c -> c.getMonth() == 2).findFirst().orElseThrow().getSource());
    }

    @Test
    void preview_listsWhatWouldBePosted_withoutWritingAnything() {
        LeaveEmployeeProfile p = new LeaveEmployeeProfile(emp, "Female", null, null, "FULL_TIME", LocalDate.of(2020, 1, 1), null, true, null);
        when(employeeRepo.findLeaveProfiles()).thenReturn(List.of(p));
        when(typeRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(paid));
        when(employeeRepo.findNamesByUserIds(Set.of(emp))).thenReturn(List.<Object[]>of(new Object[] {emp, "Asha"}));
        when(employeeRepo.findCodesByUserIds(Set.of(emp))).thenReturn(List.<Object[]>of(new Object[] {emp, "E1"}));
        policy("12", LeaveEntitlementPolicy.QUARTERLY);
        plans.accrual(paid, new LeavePlanCatalog.Accrual("END", null, new BigDecimal("2")));
        unpaidLeave(Map.of(5, "4")); // Q2 over the limit

        var preview = service.preview(LocalDate.of(2025, 10, 1)); // Q1-Q3 are over
        assertEquals(3, preview.lines().size());
        assertEquals(List.of(4, 5, 6), preview.lines().get(1).months());
        assertEquals("PAUSED", preview.lines().get(1).status());
        assertTrue(preview.lines().get(1).reason().startsWith("Unpaid leave 4 day(s)"), preview.lines().get(1).reason());
        assertEquals(LocalDate.of(2025, 7, 1), preview.lines().get(1).creditDate());
        assertDays("6", preview.creditDays());
        assertEquals(1, preview.paused());
        assertEquals("Asha", preview.lines().get(0).employeeName());
        assertTrue(ledger.isEmpty(), "a dry run writes nothing");
        verify(creditRepo, never()).insertIfAbsent(any(), any(), any(), anyInt(), anyInt(), any());
        verify(creditRepo, never()).insertPausedIfAbsent(any(), any(), any(), anyInt(), anyInt());
    }

    // ── re-crediting after a plan change (LV-01) ──

    @Test
    void reconcile_addsTheDifferenceForCreditedMonths_orResetsAMigratedBalance() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        policy("24", M); // was 12 a year: 1 per month, now 2
        balance = LeaveBalance.builder().employeeUserId(emp).leaveType(paid).year(2027)
                .totalDays(new BigDecimal("4.5")).usedDays(BigDecimal.ZERO).build(); // 3 credits + a 1.5 manual top-up
        for (int m = 1; m <= 3; m++) {
            ledger.add(LeaveAccrualCredit.builder().year(2027).month(m).days(BigDecimal.ONE).source("ACCRUAL").build());
        }
        when(balanceRepo.findByEmployeeUserIdInAndYearGreaterThanEqual(List.of(emp), 2027)).thenReturn(List.of(balance));
        assertEquals(1, service.reconcile(List.of(emp), LocalDate.of(2027, 1, 1), true));
        assertDays("7.5", balance.getTotalDays());
        ledger.forEach(c -> assertDays("2", c.getDays()));

        // A migrated (pre-crediting) balance has no per-month figures: its credited total becomes the new shares.
        ledger.clear();
        balance.setTotalDays(new BigDecimal("45"));
        for (int m = 1; m <= 12; m++) {
            ledger.add(LeaveAccrualCredit.builder().year(2027).month(m).days(BigDecimal.ZERO).source("MIGRATION").build());
        }
        assertEquals(0, service.reconcile(List.of(emp), LocalDate.of(2027, 1, 1), false)); // only with "re-credit"
        assertDays("45", balance.getTotalDays());
        service.reconcile(List.of(emp), LocalDate.of(2027, 1, 1), true);
        assertDays("24", balance.getTotalDays());
        assertTrue(ledger.stream().allMatch(c -> "ACCRUAL".equals(c.getSource())));
    }

    /** Plan A gives 12 days a year, plan B 24; the employee is allocated to B from 1 July 2025. */
    private LeavePlanCatalog switchToRicherPlanInJuly(String frequencyA, boolean bHasPaid) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), va = UUID.randomUUID(), vb = UUID.randomUUID();
        Map<UUID, Map<UUID, LeavePlanCatalog.TypeRule>> rules = new java.util.HashMap<>();
        rules.put(va, Map.of(paid.getId(), new LeavePlanCatalog.TypeRule(UUID.randomUUID(), a, va, paid.getId(),
                new BigDecimal("12"), frequencyA, false, null, null, LeaveType.CF_FIRST, LeaveEligibilityService.Rules.EVERYONE)));
        rules.put(vb, bHasPaid ? Map.of(paid.getId(), new LeavePlanCatalog.TypeRule(UUID.randomUUID(), b, vb, paid.getId(),
                new BigDecimal("24"), Y, false, null, null, LeaveType.CF_FIRST, LeaveEligibilityService.Rules.EVERYONE)) : Map.of());
        LeavePlanCatalog catalog = new LeavePlanCatalog(
                Map.of(a, com.nforce.onehr.entity.LeavePlan.builder().id(a).name("A").orgDefault(true).build(),
                       b, com.nforce.onehr.entity.LeavePlan.builder().id(b).name("B").build()),
                Map.of(a, List.of(com.nforce.onehr.entity.LeavePlanVersion.builder().id(va).planId(a).version(1)
                                .effectiveFrom(LocalDate.of(2000, 1, 1)).build()),
                       b, List.of(com.nforce.onehr.entity.LeavePlanVersion.builder().id(vb).planId(b).version(1)
                                .effectiveFrom(LocalDate.of(2000, 1, 1)).build())),
                rules,
                Map.of(emp, List.of(com.nforce.onehr.entity.LeavePlanAllocation.builder().employeeUserId(emp).planId(b)
                        .effectiveFrom(LocalDate.of(2025, 7, 1)).build())),
                Map.of(), null);
        when(plans.service().catalog(any())).thenReturn(catalog);
        return catalog;
    }

    @Test
    void planSwitchMidYear_creditsEachMonthUnderThePlanInEffectThatMonth() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        switchToRicherPlanInJuly(Y, true);
        // Jan-Jun under A (12/yr -> 1 a month) + Jul-Dec under B (24/yr -> 2 a month)
        assertDays("18", service.accrue(emp, paid, 2025));
        assertDays("18", balance.getTotalDays());
    }

    @Test
    void planSwitchAfterTheYearWasCredited_reCreditsTheMonthsFromTheSwitch() {
        employee(LocalDate.of(2020, 1, 1), null, true);
        balance = LeaveBalance.builder().employeeUserId(emp).leaveType(paid).year(2025)
                .totalDays(new BigDecimal("12")).usedDays(BigDecimal.ZERO).build();
        for (int m = 1; m <= 12; m++) { // all of 2025 credited up front under plan A
            ledger.add(LeaveAccrualCredit.builder().year(2025).month(m).days(BigDecimal.ONE).source("ACCRUAL").build());
        }
        when(balanceRepo.findByEmployeeUserIdInAndYearGreaterThanEqual(List.of(emp), 2025)).thenReturn(List.of(balance));

        switchToRicherPlanInJuly(Y, true);
        assertEquals(1, service.reconcile(List.of(emp), LocalDate.of(2025, 7, 1), false));
        assertDays("18", balance.getTotalDays()); // 6 x 1 + 6 x 2

        // A plan without the type takes the not-yet-earned months back.
        switchToRicherPlanInJuly(Y, false);
        service.reconcile(List.of(emp), LocalDate.of(2025, 7, 1), false);
        assertDays("6", balance.getTotalDays());
    }
}
