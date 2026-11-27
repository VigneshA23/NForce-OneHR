package com.nforce.onehr.service;

import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** LV-03: credit periods (monthly, quarterly, half-yearly, yearly), credit timing and the pause window. */
class LeaveAccrualPeriodsTest {

    private static final String M = LeaveEntitlementPolicy.MONTHLY;
    private static final String Q = LeaveEntitlementPolicy.QUARTERLY;
    private static final String H = LeaveEntitlementPolicy.HALF_YEARLY;
    private static final String Y = LeaveEntitlementPolicy.YEARLY;
    private static final LeavePlanCatalog.Accrual START = LeavePlanCatalog.Accrual.DEFAULT;
    private static final LeavePlanCatalog.Accrual END = new LeavePlanCatalog.Accrual("END", null, null);

    private static LeaveAccrualService.MonthRule rule(String quota, String frequency, LeavePlanCatalog.Accrual accrual) {
        return new LeaveAccrualService.MonthRule(null, new BigDecimal(quota), frequency, LeaveAccrualService.Proration.DEFAULT, accrual);
    }

    private static Map<Integer, BigDecimal> schedule(LeaveAccrualService.MonthRule r, LocalDate joining, LocalDate lastWorkingDay) {
        return LeaveAccrualService.schedule(m -> r, joining, lastWorkingDay, 2026);
    }

    private static List<LeaveAccrualService.Credit> credits(LeaveAccrualService.MonthRule r, LocalDate joining, LocalDate lastWorkingDay) {
        return LeaveAccrualService.credits(schedule(r, joining, lastWorkingDay), m -> r, 2026, lastWorkingDay);
    }

    private static BigDecimal days(Map<Integer, BigDecimal> s, List<Integer> months) {
        return months.stream().map(s::get).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void quarterly_atTheStart_creditsEachQuarterOnItsFirstDay() {
        LeaveAccrualService.MonthRule r = rule("10", Q, START);
        List<LeaveAccrualService.Credit> c = credits(r, null, null);
        assertEquals(List.of(List.of(1, 2, 3), List.of(4, 5, 6), List.of(7, 8, 9), List.of(10, 11, 12)),
                c.stream().map(LeaveAccrualService.Credit::months).toList());
        assertEquals(List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 10, 1)),
                c.stream().map(LeaveAccrualService.Credit::creditDate).toList());
        Map<Integer, BigDecimal> s = schedule(r, null, null);
        assertDays("2.5", days(s, List.of(1, 2, 3))); // 0.83 + 0.84 + 0.83, a whole quarter is not rounded
        assertDays("10", days(s, List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)));
    }

    @Test
    void atTheEnd_creditsOnceThePeriodIsOver() {
        List<LeaveAccrualService.Credit> q = credits(rule("10", Q, END), null, null);
        assertEquals(LocalDate.of(2026, 4, 1), q.get(0).creditDate());
        assertEquals(LocalDate.of(2027, 1, 1), q.get(3).creditDate()); // Q4: on 1 January
        List<LeaveAccrualService.Credit> m = credits(rule("12", M, END), null, null);
        assertEquals(LocalDate.of(2026, 3, 1), m.get(1).creditDate()); // February's credit on 1 March
        Map<Integer, BigDecimal> s = schedule(rule("12", M, END), null, null);
        assertEquals(Set.of(1), LeaveAccrualService.dueMonths(s, mm -> rule("12", M, END), 2026, LocalDate.of(2026, 2, 28)));
        assertEquals(Set.of(1, 2), LeaveAccrualService.dueMonths(s, mm -> rule("12", M, END), 2026, LocalDate.of(2026, 3, 1)));
    }

    @Test
    void halfYearly_leaver_lastPeriodCreditedTheDayAfterTheLastWorkingDay_andRounded() {
        LocalDate lwd = LocalDate.of(2026, 8, 20);
        LeaveAccrualService.MonthRule r = rule("15", H, END);
        List<LeaveAccrualService.Credit> c = credits(r, null, lwd);
        assertEquals(2, c.size());
        assertEquals(LocalDate.of(2026, 7, 1), c.get(0).creditDate());
        assertEquals(List.of(7, 8), c.get(1).months());
        assertEquals(LocalDate.of(2026, 8, 21), c.get(1).creditDate());
        Map<Integer, BigDecimal> s = schedule(r, null, lwd);
        assertDays("7.5", days(s, List.of(1, 2, 3, 4, 5, 6)));
        assertDays("2.5", days(s, List.of(7, 8)));
    }

    @Test
    void quarterly_joiner_firstPartQuarterCreditedAtOnce_andRoundedPerThePlan() {
        LeaveAccrualService.MonthRule r = rule("10", Q, START);
        Map<Integer, BigDecimal> s = schedule(r, LocalDate.of(2026, 2, 10), null);
        assertDays("1.5", days(s, List.of(2, 3))); // 0.84 + 0.83 = 1.67, nearest half day
        List<LeaveAccrualService.Credit> c = credits(r, LocalDate.of(2026, 2, 10), null);
        assertEquals(List.of(2, 3), c.get(0).months());
        assertEquals(LocalDate.of(2026, 2, 1), c.get(0).creditDate());
        assertDays("9", days(s, List.copyOf(s.keySet())));
    }

    @Test
    void yearlyAndMonthly_creditDatesAreUnchanged() {
        assertEquals(LocalDate.of(2026, 1, 1), credits(rule("15", Y, START), null, null).get(0).creditDate());
        assertEquals(1, credits(rule("15", Y, START), null, null).size());
        // a yearly joiner in October: the rest of the year at once, on 1 October
        assertEquals(LocalDate.of(2026, 10, 1), credits(rule("15", Y, START), LocalDate.of(2026, 10, 20), null).get(0).creditDate());
        assertEquals(12, credits(rule("15", M, START), null, null).size());
    }

    @Test
    void pauseWindow_isThePeriodBefore_whenCreditedAtTheStart_andThePeriodItself_atTheEnd() {
        LeaveAccrualService.Credit q2start = credits(rule("10", Q, START), null, null).get(1);
        assertEquals(LocalDate.of(2026, 1, 1), q2start.pauseFrom());
        assertEquals(LocalDate.of(2026, 3, 31), q2start.pauseTo());
        LeaveAccrualService.Credit q1start = credits(rule("10", Q, START), null, null).get(0);
        assertEquals(LocalDate.of(2025, 10, 1), q1start.pauseFrom()); // the previous year's Q4
        LeaveAccrualService.Credit q2end = credits(rule("10", Q, END), null, null).get(1);
        assertEquals(LocalDate.of(2026, 4, 1), q2end.pauseFrom());
        assertEquals(LocalDate.of(2026, 6, 30), q2end.pauseTo());
    }

    private static LeaveAccrualPauseFacts.Facts facts(String lop, String unpaid) {
        return new LeaveAccrualPauseFacts.Facts() {
            @Override public BigDecimal lopDays(LocalDate from, LocalDate to) { return new BigDecimal(lop); }
            @Override public BigDecimal unpaidDays(LocalDate from, LocalDate to) { return new BigDecimal(unpaid); }
        };
    }

    @Test
    void pause_onlyAboveTheLimit_lopOrUnpaid() {
        LeavePlanCatalog.Accrual pausing = new LeavePlanCatalog.Accrual("START", new BigDecimal("3"), new BigDecimal("5"));
        LeaveAccrualService.Credit c = credits(rule("12", M, pausing), null, null).get(2);
        assertNull(LeaveAccrualService.pauseReason(c, facts("3", "5")), "at the limit is not over it");
        assertTrue(LeaveAccrualService.pauseReason(c, facts("3.5", "0")).startsWith("Loss of pay 3.5 day(s) from 2026-02-01 to 2026-02-28"));
        assertTrue(LeaveAccrualService.pauseReason(c, facts("0", "6")).startsWith("Unpaid leave 6 day(s)"));
        // no limits set: never paused, and nothing is looked up
        LeaveAccrualService.Credit plain = credits(rule("12", M, START), null, null).get(2);
        assertNull(LeaveAccrualService.pauseReason(plain, new LeaveAccrualPauseFacts.Facts() {
            @Override public BigDecimal lopDays(LocalDate from, LocalDate to) { throw new AssertionError(); }
            @Override public BigDecimal unpaidDays(LocalDate from, LocalDate to) { throw new AssertionError(); }
        }));
    }

    @Test
    void dues_skipPostedMonths_andCreditsNotYetDated() {
        LeaveAccrualService.MonthRule r = rule("10", Q, START);
        Map<Integer, BigDecimal> s = schedule(r, null, null);
        List<LeaveAccrualService.Due> d = LeaveAccrualService.dues(s, m -> r, 2026, null, Set.of(1, 2, 3, 4),
                LocalDate.of(2026, 7, 1), LeaveAccrualPauseFacts.Facts.NONE);
        assertEquals(List.of(List.of(5, 6), List.of(7, 8, 9)), d.stream().map(LeaveAccrualService.Due::months).toList());
        assertFalse(d.get(0).paused());
    }
}
