package com.nforce.onehr.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkHoursCalculatorTest {

    @Test
    void minutesToPercent_computesPercentOfExpected() {
        assertEquals(50.0, WorkHoursCalculator.minutesToPercent(240, 480L));
        assertEquals(100.0, WorkHoursCalculator.minutesToPercent(480, 480L));
        assertEquals(0.0, WorkHoursCalculator.minutesToPercent(0, 480L));
    }

    @Test
    void minutesToPercent_returnsNull_whenMinutesMissing() {
        assertNull(WorkHoursCalculator.minutesToPercent((Integer) null, 480L));
        assertNull(WorkHoursCalculator.minutesToPercent((Long) null, 480L));
    }

    @Test
    void minutesToPercent_longOverload_computesPercentOfExpected_forCycleAggregatedTotals() {
        // Weekly/monthly aggregation sums minutes as a long — same formula, wider input type.
        assertEquals(50.0, WorkHoursCalculator.minutesToPercent(1200L, 2400L));
        assertNull(WorkHoursCalculator.minutesToPercent(1200L, 0L));
    }

    @Test
    void minutesToPercent_returnsNull_whenExpectedMinutesMissingOrNonPositive() {
        assertNull(WorkHoursCalculator.minutesToPercent(100, null));
        assertNull(WorkHoursCalculator.minutesToPercent(100, 0L));
        assertNull(WorkHoursCalculator.minutesToPercent(100, -10L));
    }

    @Test
    void percentToMinutes_isTheInverseOfMinutesToPercent() {
        assertEquals(240L, WorkHoursCalculator.percentToMinutes(50.0, 480L));
        assertEquals(480L, WorkHoursCalculator.percentToMinutes(100.0, 480L));
    }

    @Test
    void percentToMinutes_returnsNull_whenEitherInputMissing() {
        assertNull(WorkHoursCalculator.percentToMinutes(null, 480L));
        assertNull(WorkHoursCalculator.percentToMinutes(50.0, null));
        assertNull(WorkHoursCalculator.percentToMinutes(50.0, 0L));
    }
}
