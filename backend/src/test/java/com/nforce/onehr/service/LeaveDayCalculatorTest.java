package com.nforce.onehr.service;

import com.nforce.onehr.entity.LeaveDurationType;
import com.nforce.onehr.entity.LeaveRequest;
import com.nforce.onehr.entity.LeaveType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** LV-07: the leave day count — working days, the sandwich rule and its options, across requests. */
class LeaveDayCalculatorTest {

    /** Friday 5 Mar 2027; Saturday/Sunday off; Wednesday 10 Mar 2027 a holiday. */
    private static final LocalDate FRI = LocalDate.of(2027, 3, 5);
    private static final LocalDate MON = FRI.plusDays(3);
    private static final LocalDate HOLIDAY = LocalDate.of(2027, 3, 10);

    private final LeaveCalendar.Days days = new LeaveCalendar.Days() {
        @Override
        public String holiday(LocalDate date) {
            return date.equals(HOLIDAY) ? "Holi" : null;
        }

        @Override
        public boolean weeklyOff(LocalDate date) {
            return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        }
    };

    private static LeavePlanCatalog.Sandwich on(String appliesTo, String minDays, boolean across) {
        return new LeavePlanCatalog.Sandwich(true, appliesTo, minDays == null ? null : new BigDecimal(minDays), across);
    }

    private LeaveDayCalculator.Result count(LocalDate start, LocalDate end, LeavePlanCatalog.Sandwich s) {
        return LeaveDayCalculator.count(start, end, null, false, s, days, List.of(), null);
    }

    private static void assertDays(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void sandwichOff_countsOnlyWorkingDays() {
        LeaveDayCalculator.Result r = count(FRI, MON, LeavePlanCatalog.Sandwich.OFF);
        assertDays("2", r.total());
        assertEquals("2 days", r.summary());
        assertEquals(List.of("WORKING", "WEEKLY_OFF", "WEEKLY_OFF", "WORKING"), r.days().stream().map(LeaveDayCalculator.Day::kind).toList());
    }

    @Test
    void sandwichOn_countsTheWeekendBetweenLeaveDays() {
        LeaveDayCalculator.Result r = count(FRI, MON, on("BOTH", null, false));
        assertDays("4", r.total());
        assertEquals("4 days: 2 working + 2 sandwiched", r.summary());
        assertEquals(List.of(FRI.plusDays(1), FRI.plusDays(2)), List.copyOf(r.counts().sandwiched().keySet()));
    }

    @Test
    void leadingAndTrailingNonWorkingDays_areNeverCounted() {
        // Saturday to the next Sunday: only Mon-Fri (with the Wednesday holiday sandwiched) count.
        LeaveDayCalculator.Result r = count(FRI.plusDays(1), FRI.plusDays(9), on("BOTH", null, false));
        assertDays("5", r.total());
        assertDays("1", r.sandwichedDays()); // the holiday
    }

    @Test
    void appliesTo_holidaysOrWeekOffsOnly() {
        // Fri 5 to Thu 11 Mar: weekend 6-7, holiday Wed 10.
        LocalDate thu = FRI.plusDays(6);
        assertDays("6", count(FRI, thu, on("WEEK_OFFS", null, false)).total()); // 4 working + Sat/Sun
        assertDays("5", count(FRI, thu, on("HOLIDAYS", null, false)).total());  // 4 working + Holi
        assertDays("7", count(FRI, thu, on("BOTH", null, false)).total());
    }

    @Test
    void minimumDays_appliesTheRuleOnlyToLongerLeave() {
        assertDays("2", count(FRI, MON, on("BOTH", "3", false)).total()); // 2 working days < 3
        assertDays("5", count(FRI, MON.plusDays(1), on("BOTH", "3", false)).total()); // 3 working + 2 sandwiched
    }

    @Test
    void onlyNonWorkingDays_areRefused_aSingleOneWithThePreviousMessage() {
        IllegalArgumentException range = assertThrows(IllegalArgumentException.class, () -> count(FRI.plusDays(1), FRI.plusDays(2), on("BOTH", null, false)));
        assertTrue(range.getMessage().contains("covers only weekly offs and holidays"), range.getMessage());
        IllegalArgumentException one = assertThrows(IllegalArgumentException.class, () -> count(HOLIDAY, HOLIDAY, LeavePlanCatalog.Sandwich.OFF));
        assertEquals("Leave cannot be applied for Wednesday, 10 Mar 2027 — it is a holiday (Holi). Please choose only your working days.",
                one.getMessage());
    }

    @Test
    void halfDayAndHourly_takeTheirFraction_onAWorkingDayOnly() {
        assertDays("0.5", LeaveDayCalculator.count(FRI, FRI, new BigDecimal("0.5"), false, LeavePlanCatalog.Sandwich.OFF, days, List.of(), null).total());
        assertThrows(IllegalArgumentException.class, () -> LeaveDayCalculator.count(FRI.plusDays(1), FRI.plusDays(1),
                new BigDecimal("0.25"), false, LeavePlanCatalog.Sandwich.OFF, days, List.of(), null));
    }

    @Test
    void perOccurrenceLeave_countsCalendarDays() {
        assertDays("10", LeaveDayCalculator.count(FRI, FRI.plusDays(9), null, true, on("BOTH", null, false), null, List.of(), null).total());
    }

    private static LeaveRequest neighbour(LocalDate start, LocalDate end, String days, LocalDateTime createdAt) {
        return LeaveRequest.builder().id(UUID.randomUUID()).leaveType(LeaveType.builder().id(UUID.randomUUID()).build())
                .startDate(start).endDate(end).totalDays(new BigDecimal(days)).status("APPROVED")
                .durationType(LeaveDurationType.FULL_DAY).createdAt(createdAt).build();
    }

    @Test
    void acrossRequests_aLaterRequestCarriesTheGapToAnEarlierOne() {
        LocalDateTime now = LocalDateTime.of(2027, 2, 1, 10, 0);
        LeaveRequest friday = neighbour(FRI, FRI, "1", now.minusDays(1));
        LeaveDayCalculator.Result monday = LeaveDayCalculator.count(MON, MON, null, false, on("WEEK_OFFS", null, true), days, List.of(friday), null);
        assertDays("3", monday.total());
        assertEquals(List.of(FRI.plusDays(1), FRI.plusDays(2)), List.copyOf(monday.counts().sandwiched().keySet()));

        // Editing the older Friday request doesn't charge the gap a second time.
        LeaveRequest mondayReq = neighbour(MON, MON, "3", now);
        assertDays("1", LeaveDayCalculator.count(FRI, FRI, null, false, on("WEEK_OFFS", null, true), days, List.of(mondayReq),
                now.minusDays(1)).total());
        // Off unless "across requests" is on; half-day neighbours don't count; the minimum applies to both together.
        assertDays("1", LeaveDayCalculator.count(MON, MON, null, false, on("WEEK_OFFS", null, false), days, List.of(friday), null).total());
        LeaveRequest halfFriday = neighbour(FRI, FRI, "0.5", now.minusDays(1));
        halfFriday.setHalfDay(true);
        assertDays("1", LeaveDayCalculator.count(MON, MON, null, false, on("WEEK_OFFS", null, true), days, List.of(halfFriday), null).total());
        assertDays("1", LeaveDayCalculator.count(MON, MON, null, false, on("WEEK_OFFS", "3", true), days, List.of(friday), null).total());
        assertDays("3", LeaveDayCalculator.count(MON, MON, null, false, on("WEEK_OFFS", "2", true), days, List.of(friday), null).total());
    }

    @Test
    void dayCounts_splitByYear_cutOff_andRoundTrip() {
        TreeMap<LocalDate, BigDecimal> working = new TreeMap<>(Map.of(LocalDate.of(2026, 12, 31), BigDecimal.ONE,
                LocalDate.of(2027, 1, 4), BigDecimal.ONE, LocalDate.of(2027, 1, 5), BigDecimal.ONE));
        TreeMap<LocalDate, BigDecimal> sandwiched = new TreeMap<>(Map.of(LocalDate.of(2027, 1, 2), BigDecimal.ONE,
                LocalDate.of(2027, 1, 3), BigDecimal.ONE));
        LeaveDayCounts c = new LeaveDayCounts(working, sandwiched);
        assertEquals(Map.of(2026, BigDecimal.ONE, 2027, new BigDecimal("4")), c.byYear());
        assertDays("3", c.onOrBefore(2027, LocalDate.of(2027, 1, 4)));
        LeaveDayCounts back = LeaveDayCounts.parse(c.toJson());
        assertDays("5", back.total());
        assertDays("2", back.sandwichedTotal());
        assertNull(LeaveDayCounts.parse(null));
        // The balance split uses them; without them (a pre-V323 request) every date in the range counts.
        assertEquals(c.byYear(), LeaveBalanceService.daysByYear(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 5), c.total(), c));
        assertEquals(new BigDecimal("5"), LeaveBalanceService.daysByYear(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 5),
                new BigDecimal("6"), null).get(2027));
    }
}
