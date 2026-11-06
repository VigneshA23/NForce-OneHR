package com.nforce.onehr.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure date-math tests for PayGroup#resolveCycle — no Spring context needed. */
class PayGroupTest {

    private PayGroup crossMonth(int startDay, int endDay) {
        return PayGroup.builder().cycleStartDay(startDay).cycleEndDay(endDay).build();
    }

    @Test
    void resolveCycle_26to25_octToNov_is31Days() {
        // US-C19's own example: "26 Oct-25 Nov = 31."
        PayGroup group = crossMonth(26, 25);

        PayCycleWindow window = group.resolveCycle(LocalDate.of(2026, 11, 10));

        assertEquals(LocalDate.of(2026, 10, 26), window.start());
        assertEquals(LocalDate.of(2026, 11, 25), window.end());
        assertEquals(31, window.dayCount());
    }

    @Test
    void resolveCycle_26to25_sepToOct_is30Days() {
        // US-C19's own example: "26 Sep-25 Oct = 30."
        PayGroup group = crossMonth(26, 25);

        PayCycleWindow window = group.resolveCycle(LocalDate.of(2026, 10, 1));

        assertEquals(LocalDate.of(2026, 9, 26), window.start());
        assertEquals(LocalDate.of(2026, 10, 25), window.end());
        assertEquals(30, window.dayCount());
    }

    @Test
    void resolveCycle_referenceDateOnTheEndDay_belongsToThatCycle() {
        PayGroup group = crossMonth(26, 25);

        PayCycleWindow window = group.resolveCycle(LocalDate.of(2026, 11, 25));

        assertEquals(LocalDate.of(2026, 10, 26), window.start());
        assertEquals(LocalDate.of(2026, 11, 25), window.end());
    }

    @Test
    void resolveCycle_referenceDateOnTheStartDay_belongsToTheNewCycle() {
        PayGroup group = crossMonth(26, 25);

        PayCycleWindow window = group.resolveCycle(LocalDate.of(2026, 11, 26));

        assertEquals(LocalDate.of(2026, 11, 26), window.start());
        assertEquals(LocalDate.of(2026, 12, 25), window.end());
    }

    @Test
    void resolveCycle_crossesAYearBoundary() {
        PayGroup group = crossMonth(26, 25);

        PayCycleWindow window = group.resolveCycle(LocalDate.of(2027, 1, 10));

        assertEquals(LocalDate.of(2026, 12, 26), window.start());
        assertEquals(LocalDate.of(2027, 1, 25), window.end());
    }

    @Test
    void resolveCycle_startDayClampedInShortMonth() {
        // Start day 30 doesn't exist in February — clamp to Feb's actual last day.
        PayGroup group = crossMonth(30, 25);

        PayCycleWindow window = group.resolveCycle(LocalDate.of(2026, 3, 10));

        assertEquals(LocalDate.of(2026, 2, 28), window.start());
        assertEquals(LocalDate.of(2026, 3, 25), window.end());
    }

    @Test
    void resolveCycle_startDayClampedInLeapFebruary() {
        PayGroup group = crossMonth(30, 25);

        PayCycleWindow window = group.resolveCycle(LocalDate.of(2028, 3, 10));

        assertEquals(LocalDate.of(2028, 2, 29), window.start());
    }

    @Test
    void resolveCycle_sameMonthWindow_firstToTwentyEighth() {
        PayGroup group = crossMonth(1, 28);

        PayCycleWindow window = group.resolveCycle(LocalDate.of(2026, 6, 15));

        assertEquals(LocalDate.of(2026, 6, 1), window.start());
        assertEquals(LocalDate.of(2026, 6, 28), window.end());
        assertEquals(28, window.dayCount());
    }

    @Test
    void resolveCycle_sameMonthWindow_referenceDateBeforeStartDay_belongsToPreviousMonth() {
        // A rare edge case for a same-month window whose start day isn't the 1st (e.g. 5th-28th):
        // the 2nd of the month hasn't reached this month's cycle yet, so it belongs to last
        // month's.
        PayGroup group = crossMonth(5, 28);

        PayCycleWindow window = group.resolveCycle(LocalDate.of(2026, 6, 2));

        assertEquals(LocalDate.of(2026, 5, 5), window.start());
        assertEquals(LocalDate.of(2026, 5, 28), window.end());
    }
}
