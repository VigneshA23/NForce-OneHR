package com.nforce.onehr.service;

import java.time.LocalDate;

/**
 * Effective-dated allocation ranges, shared by penalization policy and leave plan allocations (LV-01)
 * so both follow one model: [from, to] inclusive, a null {@code to} is open-ended, a range ending
 * before today is history and is never edited or removed.
 */
public final class AllocationRanges {

    public static final String CURRENT = "CURRENT";
    public static final String FUTURE = "FUTURE";
    public static final String HISTORICAL = "HISTORICAL";

    private AllocationRanges() {}

    public static String status(LocalDate from, LocalDate to, LocalDate today) {
        if (to != null && to.isBefore(today)) return HISTORICAL;
        if (from.isAfter(today)) return FUTURE;
        return CURRENT;
    }

    public static void validate(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("Effective To cannot be before Effective From");
        }
    }
}
