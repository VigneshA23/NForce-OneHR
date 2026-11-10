package com.nforce.onehr.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** US-C17: the fiscal-year window — boundary months are the easy place to be off by one. */
class FiscalYearTest {

    @Test
    void april_start_dateInMarch_belongsToTheYearThatStartedLastApril() {
        FiscalYear fy = FiscalYear.containing(LocalDate.of(2027, 3, 31), 4);

        assertEquals(LocalDate.of(2026, 4, 1), fy.start());
        assertEquals(LocalDate.of(2027, 3, 31), fy.end());
        assertEquals("FY 2026-27", fy.label());
    }

    @Test
    void april_start_firstDayOfApril_startsTheNewYear() {
        FiscalYear fy = FiscalYear.containing(LocalDate.of(2027, 4, 1), 4);

        assertEquals(LocalDate.of(2027, 4, 1), fy.start());
        assertEquals(LocalDate.of(2028, 3, 31), fy.end());
        assertEquals("FY 2027-28", fy.label());
    }

    @Test
    void april_start_lastDayOfMarch_andFirstDayOfApril_fallInDifferentYears() {
        FiscalYear before = FiscalYear.containing(LocalDate.of(2027, 3, 31), 4);
        FiscalYear after = FiscalYear.containing(LocalDate.of(2027, 4, 1), 4);

        assertEquals(before.end().plusDays(1), after.start());
    }

    @Test
    void april_start_midYearDates() {
        assertEquals(LocalDate.of(2026, 4, 1), FiscalYear.containing(LocalDate.of(2026, 11, 9), 4).start());
        assertEquals(LocalDate.of(2026, 4, 1), FiscalYear.containing(LocalDate.of(2027, 1, 1), 4).start());
        assertEquals(LocalDate.of(2026, 4, 1), FiscalYear.containing(LocalDate.of(2026, 12, 31), 4).start());
    }

    @Test
    void calendarYear_start_isJanuaryToDecember() {
        FiscalYear fy = FiscalYear.containing(LocalDate.of(2026, 12, 31), 1);

        assertEquals(LocalDate.of(2026, 1, 1), fy.start());
        assertEquals(LocalDate.of(2026, 12, 31), fy.end());
        assertEquals("FY 2026", fy.label());
        assertEquals(LocalDate.of(2027, 1, 1), FiscalYear.containing(LocalDate.of(2027, 1, 1), 1).start());
    }

    @Test
    void december_start_wrapsAcrossTheCalendarYear() {
        FiscalYear november = FiscalYear.containing(LocalDate.of(2026, 11, 30), 12);
        FiscalYear december = FiscalYear.containing(LocalDate.of(2026, 12, 1), 12);

        assertEquals(LocalDate.of(2025, 12, 1), november.start());
        assertEquals(LocalDate.of(2026, 11, 30), november.end());
        assertEquals(LocalDate.of(2026, 12, 1), december.start());
        assertEquals(LocalDate.of(2027, 11, 30), december.end());
    }

    @Test
    void yearThatEndsInLeapFebruary_endsOnTheLastDayOfIt() {
        // March start: the year starting 1 Mar 2027 ends 29 Feb 2028 (leap year).
        FiscalYear fy = FiscalYear.containing(LocalDate.of(2027, 6, 15), 3);

        assertEquals(LocalDate.of(2027, 3, 1), fy.start());
        assertEquals(LocalDate.of(2028, 2, 29), fy.end());
    }

    @Test
    void label_padsTheTwoDigitEndYear() {
        assertEquals("FY 2008-09", FiscalYear.containing(LocalDate.of(2008, 5, 1), 4).label());
        assertEquals("FY 2099-00", FiscalYear.containing(LocalDate.of(2099, 5, 1), 4).label());
    }

    @Test
    void startMonthOutOfRange_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> FiscalYear.containing(LocalDate.of(2026, 1, 1), 0));
        assertThrows(IllegalArgumentException.class, () -> FiscalYear.containing(LocalDate.of(2026, 1, 1), 13));
    }
}
