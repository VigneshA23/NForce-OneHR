package com.nforce.onehr.entity;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The resolved [start, end] (inclusive) of one pay cycle, plus its day count — see
 * {@link PayGroup#resolveCycle(LocalDate)}. {@code dayCount} is US-C19's own LOP/proration
 * divisor: "26 Oct-25 Nov = 31, 26 Sep-25 Oct = 30."
 */
public record PayCycleWindow(LocalDate start, LocalDate end) {

    public int dayCount() {
        return (int) ChronoUnit.DAYS.between(start, end) + 1;
    }
}
