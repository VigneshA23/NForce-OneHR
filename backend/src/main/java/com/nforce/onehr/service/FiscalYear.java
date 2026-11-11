package com.nforce.onehr.service;

import java.time.LocalDate;

/**
 * US-C17 — the fiscal year containing a given date, for a configurable start month. {@code
 * startMonth} 4 gives April–March (the Indian standard), 1 gives the calendar year.
 *
 * <p>{@code end} is the LAST day of the year (the day before the next start), so both ends are
 * inclusive and a date range check is simply {@code !d.isBefore(start) && !d.isAfter(end)}.
 */
public record FiscalYear(LocalDate start, LocalDate end, String label) {

    public static FiscalYear containing(LocalDate date, int startMonth) {
        if (startMonth < 1 || startMonth > 12) {
            throw new IllegalArgumentException("Fiscal year start month must be between 1 and 12");
        }
        // A date before this calendar year's start month still belongs to the year that began last calendar year.
        int startYear = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        LocalDate start = LocalDate.of(startYear, startMonth, 1);
        LocalDate end = start.plusYears(1).minusDays(1);
        String label = startMonth == 1
                ? "FY " + startYear
                : "FY " + startYear + "-" + String.format("%02d", (startYear + 1) % 100);
        return new FiscalYear(start, end, label);
    }
}
