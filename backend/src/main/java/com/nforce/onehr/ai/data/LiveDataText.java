package com.nforce.onehr.ai.data;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Small, shared phrasing for provider output.
 *
 * <p>The one rule worth centralising: a capped list must say how many rows exist in total, not only
 * how many it shows. A provider that listed "the 5 most recent" with no total let the model report
 * 5 as the answer to "how many", which is exactly the silent under-count the attendance providers
 * were fixed for - so every capped list goes through {@link #listHeader}.
 */
final class LiveDataText {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

    private LiveDataText() {}

    /**
     * "{total} {noun}:" when every row is shown, otherwise a header that states the true total and
     * that only the first {@code shown} follow, in {@code order} (e.g. "most recent").
     */
    static String listHeader(int total, int shown, String noun, String order) {
        if (total <= shown) return "%d %s:".formatted(total, noun);
        return "%d %s in total; only the %d %s are listed below - say the list is partial if asked for all of them:"
                .formatted(total, noun, shown, order);
    }

    /** Joins up to {@code max} items as "- line" rows under {@link #listHeader}. */
    static <T> String cappedList(List<T> items, int max, String noun, String order, Function<T, String> line) {
        List<T> shown = items.stream().limit(max).toList();
        String rows = shown.stream().map(item -> "- " + line.apply(item)).collect(Collectors.joining("\n"));
        return listHeader(items.size(), shown.size(), noun, order) + "\n" + rows;
    }

    /** Comma-joined names, capped, with how many more there are - for "who is ..." lines. */
    static String names(Collection<String> names, int max) {
        List<String> shown = names.stream().limit(max).toList();
        String joined = String.join(", ", shown);
        int more = names.size() - shown.size();
        return more > 0 ? joined + " and " + more + " more" : joined;
    }

    /**
     * A date with its relative day spelled out when it is yesterday, today or tomorrow, e.g.
     * "2026-09-25 (today)". Given bare dates, the model has been seen doing the arithmetic wrong -
     * calling leave that ends today one that "includes today and tomorrow" - so the answer is put
     * in the data instead of left to be derived.
     */
    static String relative(LocalDate date, LocalDate today) {
        if (date == null) return "-";
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, date);
        if (days == 0) return date + " (today)";
        if (days == -1) return date + " (yesterday)";
        if (days == 1) return date + " (tomorrow)";
        return date.toString();
    }

    /** Wall-clock time as the Attendance pages show it, e.g. "09:42 AM". */
    static String clock(LocalDateTime time) {
        return time == null ? "-" : time.toLocalTime().format(CLOCK);
    }

    static String clock(LocalTime time) {
        return time == null ? "-" : time.format(CLOCK);
    }

    /** An Instant as a date in the given zone, for created/updated timestamps. */
    static String date(Instant instant, ZoneId zone) {
        return instant == null ? "-" : instant.atZone(zone).toLocalDate().toString();
    }

    /** Minutes as "7h 32m". */
    static String hoursMinutes(long minutes) {
        if (minutes < 0) minutes = 0;
        return "%dh %02dm".formatted(minutes / 60, minutes % 60);
    }

    static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || to.isBefore(from)) return 0;
        return Duration.between(from, to).toMinutes();
    }
}
