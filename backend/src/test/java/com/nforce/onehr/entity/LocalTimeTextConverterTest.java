package com.nforce.onehr.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure round-trip tests for the converter behind the shift start/end persistence fix (see its
 * own Javadoc for the full incident writeup) — no database involved, since the actual bug this
 * guards against is a JDBC/Calendar-mediated skew that only manifests through a real Postgres
 * TIME column; these tests instead pin down the converter's own text format contract, so a
 * future edit can't silently reintroduce ambiguity (e.g. reverting to LocalTime#toString(),
 * which drops trailing ":00" seconds and would round-trip fine here but not necessarily match
 * what's already stored on disk).
 */
class LocalTimeTextConverterTest {

    private final LocalTimeTextConverter converter = new LocalTimeTextConverter();

    @Test
    void convertsNullBothWays() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    // The 4 patterns from the regression suite this fix was verified against: an IST-style
    // overnight shift, a plain daytime shift, a business-hours shift, and a second overnight
    // shift with a different wrap point — deliberately not all "IST-shaped" so a hardcoded
    // offset hiding in a future edit would show up here.
    @ParameterizedTest
    @ValueSource(strings = { "15:30:00", "10:00:00", "09:00:00", "22:00:00", "00:30:00", "06:00:00", "18:00:00", "19:00:00" })
    void roundTripsExactlyWithNoSkew(String hhmmss) {
        LocalTime original = LocalTime.parse(hhmmss);
        String stored = converter.convertToDatabaseColumn(original);
        assertThat(stored).isEqualTo(hhmmss);
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(original);
    }

    @Test
    void alwaysWritesSecondsEvenWhenZero() {
        // LocalTime#toString() alone would give "9:0" -> not what we want; the converter must
        // always emit the full "HH:mm:ss" the database round-trips exactly.
        assertThat(converter.convertToDatabaseColumn(LocalTime.of(9, 0))).isEqualTo("09:00:00");
    }

    @Test
    void midnightRoundTrips() {
        assertThat(converter.convertToDatabaseColumn(LocalTime.MIDNIGHT)).isEqualTo("00:00:00");
        assertThat(converter.convertToEntityAttribute("00:00:00")).isEqualTo(LocalTime.MIDNIGHT);
    }
}
