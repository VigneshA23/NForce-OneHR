package com.nforce.onehr.service;

import com.nforce.onehr.entity.LeaveEntitlementPolicy;
import com.nforce.onehr.entity.LeavePlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** LV-04: proration of a part-year entitlement by the plan's method, cut-off day and rounding. */
class LeaveProrationTest {

    private static final String Y = LeaveEntitlementPolicy.YEARLY;
    private static final String M = LeaveEntitlementPolicy.MONTHLY;

    private static LeaveAccrualService.Proration p(String method, Integer cutoff, String rounding) {
        return new LeaveAccrualService.Proration(method, cutoff, rounding);
    }

    private static Map<Integer, BigDecimal> schedule(String quota, String frequency, LeaveAccrualService.Proration proration,
                                                     LocalDate joining, LocalDate lastWorkingDay) {
        LeaveAccrualService.MonthRule r = new LeaveAccrualService.MonthRule(null, new BigDecimal(quota), frequency, proration);
        return LeaveAccrualService.schedule(m -> r, joining, lastWorkingDay, 2026);
    }

    private static BigDecimal total(Map<Integer, BigDecimal> s) {
        return s.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void workedExample_15Days_joined1October_byMonths_is3point75_roundedTo4() {
        LocalDate oct1 = LocalDate.of(2026, 10, 1);
        assertDays("4", total(schedule("15", Y, LeaveAccrualService.Proration.DEFAULT, oct1, null)));
        assertDays("3.5", total(schedule("15", Y, p("MONTHS", null, "DOWN_HALF"), oct1, null)));
        assertDays("4", total(schedule("15", Y, p("MONTHS", null, "UP_HALF"), oct1, null)));
        assertDays("3.75", total(schedule("15", Y, p("MONTHS", null, "NONE"), oct1, null)));
    }

    @Test
    void monthsWithCutOff_joiningAfterItCountsFromTheNextMonth() {
        LeaveAccrualService.Proration cutoff15 = p("MONTHS", 15, "NEAREST_HALF");
        Map<Integer, BigDecimal> late = schedule("15", Y, cutoff15, LocalDate.of(2026, 10, 16), null);
        assertFalse(late.containsKey(10));
        assertDays("2.5", total(late)); // November + December
        assertDays("4", total(schedule("15", Y, cutoff15, LocalDate.of(2026, 10, 15), null)));
        // No cut-off (the default): the joining month always counts in full.
        assertDays("4", total(schedule("15", Y, LeaveAccrualService.Proration.DEFAULT, LocalDate.of(2026, 10, 31), null)));
    }

    @Test
    void calendarDays_countTheDaysServedInTheJoiningMonth() {
        LocalDate oct16 = LocalDate.of(2026, 10, 16); // 16 of 31 days: 1.25 × 16/31 = 0.65
        assertDays("3", total(schedule("15", Y, p("CALENDAR_DAYS", null, "NEAREST_HALF"), oct16, null))); // 3.15
        assertDays("3.5", total(schedule("15", Y, p("CALENDAR_DAYS", null, "UP_HALF"), oct16, null)));
        assertDays("3.15", total(schedule("15", Y, p("CALENDAR_DAYS", null, "NONE"), oct16, null)));
        // Monthly crediting: only the partly-served month is rounded, full months stay exact.
        Map<Integer, BigDecimal> monthly = schedule("15", M, p("CALENDAR_DAYS", null, "NEAREST_HALF"), oct16, null);
        assertDays("0.5", monthly.get(10));
        assertDays("1.25", monthly.get(11));
    }

    @Test
    void exit_prorationStopsAtTheLastWorkingDay() {
        LocalDate mar10 = LocalDate.of(2026, 3, 10);
        // Whole months: January-March = 3.75 → 4.
        assertDays("4", total(schedule("15", Y, LeaveAccrualService.Proration.DEFAULT, null, mar10)));
        // Cut-off 15: leaving before it drops March → 2.5.
        assertDays("2.5", total(schedule("15", Y, p("MONTHS", 15, "NEAREST_HALF"), null, mar10)));
        // Calendar days: March 10 of 31 days → 2.5 + 0.40 = 2.90 → 3.
        assertDays("3", total(schedule("15", Y, p("CALENDAR_DAYS", null, "NEAREST_HALF"), null, mar10)));
        // Joined and left in the same month, by calendar days: 1-10 June of 30 → 0.42 → nearest 0.5.
        assertDays("0.5", total(schedule("15", Y, p("CALENDAR_DAYS", null, "NEAREST_HALF"), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 10))));
    }

    @Test
    void roundingDownNeverMakesAMonthNegative() {
        // January in full (1.25) + 1 day of February (1.25 × 1/28 = 0.04) = 1.29 → down to 1.0.
        Map<Integer, BigDecimal> s = schedule("15", Y, p("CALENDAR_DAYS", null, "DOWN_HALF"), null, LocalDate.of(2026, 2, 1));
        assertDays("1", total(s));
        assertTrue(s.values().stream().allMatch(v -> v.signum() >= 0), s.toString());
    }

    @Test
    void theDefaultIsExactlyTheBehaviourBeforeLv04() {
        LeavePlan plan = LeavePlan.builder().name("Default").build();
        assertEquals(LeaveAccrualService.Proration.DEFAULT, LeaveAccrualService.Proration.of(plan));
        LeaveAccrualService.MonthRule legacy = new LeaveAccrualService.MonthRule(null, new BigDecimal("15"), M);
        assertEquals(LeaveAccrualService.Proration.DEFAULT, legacy.proration());
        assertDays("3.75", total(schedule("15", M, LeaveAccrualService.Proration.DEFAULT, LocalDate.of(2026, 10, 20), null)));
    }
}
