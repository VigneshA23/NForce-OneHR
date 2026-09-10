package com.nforce.onehr.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalTime;

/**
 * Binds {@link LocalTime} to a Postgres {@code TIME} column as a plain "HH:mm:ss" string,
 * instead of Hibernate's default {@code java.sql.Time}/{@code Calendar}-mediated JDBC binding.
 *
 * <p>Root-cause fix for a shift start/end time-zone-skew bug (see the incident this converter
 * was added for): this app's {@code hibernate.jdbc.time_zone: UTC} setting was meant to make
 * {@code LocalTime <-> TIME} conversion independent of the JVM's own default timezone — but a
 * live comparison of the SAME database row read by two backend instances with different JVM
 * default zones (a local dev machine on Asia/Kolkata vs. Railway on UTC — see
 * application.yml's own "the JVM default is UTC on Railway" comment) proved it wasn't fully
 * effective: whichever JVM's default zone differed from UTC introduced a spurious skew equal to
 * its own UTC offset (here, exactly -5:30) on WRITE, silently corrupting the stored value —
 * invisible to whoever set it, since a subsequent read from that SAME JVM offset happened to
 * cancel the skew back out and show the intended value, while any other JVM (Railway, UTC
 * default) read the already-corrupted raw value verbatim.
 *
 * <p>A plain string has no timezone semantics at all, so there is nothing left for any JVM's
 * default zone, any {@code Calendar}, or any JDBC driver quirk to skew — "15:30:00" written by
 * any machine, anywhere, is read back as exactly "15:30:00" by any other. This is the permanent
 * fix, not a per-shift correction: unlike {@code MigrationSeededShiftTimeFix} (a one-time
 * re-save for 3 specific shifts already known to be affected), this makes the entire class of
 * bug structurally impossible going forward, for every shift, regardless of which environment
 * last created or edited it.
 */
@Converter
public class LocalTimeTextConverter implements AttributeConverter<LocalTime, String> {

    @Override
    public String convertToDatabaseColumn(LocalTime attribute) {
        // Explicit HH:mm:ss (never LocalTime's own toString(), which drops trailing :00 seconds
        // and any nanos) — a fixed format is one less thing to reason about at the SQL end.
        return attribute == null ? null
                : String.format("%02d:%02d:%02d", attribute.getHour(), attribute.getMinute(), attribute.getSecond());
    }

    @Override
    public LocalTime convertToEntityAttribute(String dbData) {
        return dbData == null ? null : LocalTime.parse(dbData);
    }
}
