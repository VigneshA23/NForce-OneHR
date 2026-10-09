package com.nforce.onehr.ai.data;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a natural date expression in the raw question - "today", "this week", "last 7 days",
 * "last 30 days", "this month", "last month", "this quarter", "August", "from 2026-09-01 to
 * 2026-09-15", "21-09-2026 to 28-09-2026" - into a concrete range, anchored to OneHR's own
 * business date.
 *
 * <p>Deterministic and pattern-based on purpose, never the LLM: the whole point of a business-date
 * range is that it must agree with what the matching My Team screen itself would show for the same
 * words, and the model is not the authority on that (see {@code SystemPromptTemplate}'s CURRENT
 * DATE &amp; TIME section - the same reasoning that keeps "today" itself out of the model's hands).
 *
 * <p>Recognises a closed, unambiguous set of expressions. A question with none of them, or an
 * explicit range this cannot parse safely, resolves to {@code fallbackDays} - the trailing N days
 * ending today, the same default the matching My Team screen itself opens on ({@code
 * useTeamDateRange(7)} for Efforts/Negligence, 30 for Penalties/Attendance Request Reports) -
 * "use the established default range... rather than inventing a different interpretation."
 */
public final class MyTeamDateRange {

    private MyTeamDateRange() {}

    /** {@code to} is always the resolved end date; never later than the business-today it was built from. */
    public record Range(LocalDate from, LocalDate to, String label) {}

    private static final Pattern TODAY = Pattern.compile("\\btoday\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YESTERDAY = Pattern.compile("\\byesterday\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern THIS_WEEK = Pattern.compile("\\bthis\\s+week\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST_N_DAYS = Pattern.compile(
            "\\b(?:last|past|previous)\\s+(\\d{1,3})\\s+days?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST_WEEK_ROLLING = Pattern.compile(
            "\\b(?:last|past|previous)\\s+week\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern THIS_MONTH = Pattern.compile("\\bthis\\s+month\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST_MONTH = Pattern.compile("\\b(?:last|past|previous)\\s+month\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern THIS_QUARTER = Pattern.compile("\\bthis\\s+quarter\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST_QUARTER = Pattern.compile("\\b(?:last|past|previous)\\s+quarter\\b", Pattern.CASE_INSENSITIVE);
    /**
     * An explicit calendar date: ISO (2026-09-21) or day-first (21-09-2026, 21/09/2026, 21.09.2026 -
     * the order OneHR itself displays). Never month-first: a guessed date is worse than none.
     */
    private static final Pattern EXPLICIT_DATE = Pattern.compile(
            "\\b(?:(\\d{4})-(\\d{1,2})-(\\d{1,2})|(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4}))\\b");
    /**
     * A month by its full name, optionally followed by a year. "May" only with a year or after a
     * preposition, so "may I apply for leave" is never read as the month.
     */
    private static final Pattern MONTH = Pattern.compile(
            "\\b(?:(january|february|march|april|june|july|august|september|sept|october|november|december)"
                    + "|(?<=\\b(?:in|of|for|during|from|since|until|till|to|and)\\s)(may)|(may)(?=\\s+\\d{4}))"
                    + "(?:\\s*,?\\s*(\\d{4}))?\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * @param question    the raw user question, never null-checked for content - only matched against
     * @param today       the org's business-zone "today" (see {@code AttendanceRulesService})
     * @param fallbackDays the trailing window size to fall back to when nothing recognisable matched
     */
    public static Range resolve(String question, LocalDate today, int fallbackDays) {
        return named(question, today)
                // Never later than business-today, exactly like the screen's own date inputs
                // (DateRangeControl's `to` input has max=todayIsoDate()).
                .map(r -> r.to().isAfter(today) ? new Range(r.from(), today, r.label()) : r)
                .filter(r -> !r.from().isAfter(r.to()))
                .orElseGet(() -> trailing(today, fallbackDays, "the last " + fallbackDays + " days (no period was specified)"));
    }

    /**
     * The period the question itself names, if it names one - uncapped, so a question about
     * upcoming leave ("my leave in October") can still reach past today. Empty when nothing
     * recognisable was said, which is how a caller tells "asked for a period" from "did not".
     *
     * <p>Two or more explicit dates span the first to the last; two or more month names span the
     * earliest to the latest ("September, but only records from August" reads August through
     * September, so the answer can see both and point out the conflict).
     */
    public static Optional<Range> named(String question, LocalDate today) {
        String q = question == null ? "" : question;

        List<LocalDate> dates = new ArrayList<>();
        Matcher explicit = EXPLICIT_DATE.matcher(q);
        while (explicit.find()) {
            LocalDate date = explicit.group(1) != null
                    ? date(explicit.group(1), explicit.group(2), explicit.group(3))
                    : date(explicit.group(6), explicit.group(5), explicit.group(4));
            if (date != null) dates.add(date);
        }
        if (dates.size() == 1) {
            return Optional.of(new Range(dates.get(0), dates.get(0), dates.get(0).toString()));
        }
        if (dates.size() > 1) {
            LocalDate from = dates.get(0);
            LocalDate to = dates.get(dates.size() - 1);
            // Backwards ("from the 10th to the 1st") is a typo, not a range worth guessing at.
            return from.isAfter(to) ? Optional.empty() : Optional.of(new Range(from, to, from + " to " + to));
        }
        if (TODAY.matcher(q).find()) {
            return Optional.of(new Range(today, today, "today"));
        }
        if (YESTERDAY.matcher(q).find()) {
            LocalDate y = today.minusDays(1);
            return Optional.of(new Range(y, y, "yesterday (" + y + ")"));
        }
        if (THIS_WEEK.matcher(q).find()) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return Optional.of(new Range(monday, today, "this week (" + monday + " to " + today + ")"));
        }
        if (LAST_WEEK_ROLLING.matcher(q).find()) {
            return Optional.of(trailing(today, 7, "the last 7 days"));
        }
        Matcher lastN = LAST_N_DAYS.matcher(q);
        if (lastN.find()) {
            int days = Integer.parseInt(lastN.group(1));
            if (days >= 1 && days <= 366) {
                return Optional.of(trailing(today, days, "the last " + days + " days"));
            }
        }
        if (THIS_MONTH.matcher(q).find()) {
            LocalDate first = today.withDayOfMonth(1);
            return Optional.of(new Range(first, today, "this month (" + first + " to " + today + ")"));
        }
        if (LAST_MONTH.matcher(q).find()) {
            LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);
            LocalDate lastMonthStart = lastMonthEnd.withDayOfMonth(1);
            return Optional.of(new Range(lastMonthStart, lastMonthEnd, "last month (" + lastMonthStart + " to " + lastMonthEnd + ")"));
        }
        if (THIS_QUARTER.matcher(q).find()) {
            LocalDate start = quarterStart(today);
            return Optional.of(new Range(start, today, "this quarter (" + start + " to " + today + ")"));
        }
        if (LAST_QUARTER.matcher(q).find()) {
            LocalDate lastQuarterEnd = quarterStart(today).minusDays(1);
            LocalDate lastQuarterStart = quarterStart(lastQuarterEnd);
            return Optional.of(new Range(lastQuarterStart, lastQuarterEnd, "last quarter (" + lastQuarterStart + " to " + lastQuarterEnd + ")"));
        }

        List<YearMonth> months = new ArrayList<>();
        Matcher month = MONTH.matcher(q);
        while (month.find()) {
            String name = month.group(1) != null ? month.group(1) : month.group(2) != null ? month.group(2) : month.group(3);
            Month m = name.equalsIgnoreCase("sept") ? Month.SEPTEMBER : Month.valueOf(name.toUpperCase(Locale.ROOT));
            // No year given: the most recent such month - this year's, unless it has not started yet.
            int year = month.group(4) != null ? Integer.parseInt(month.group(4))
                    : m.getValue() <= today.getMonthValue() ? today.getYear() : today.getYear() - 1;
            months.add(YearMonth.of(year, m));
        }
        if (months.isEmpty()) return Optional.empty();
        YearMonth first = Collections.min(months);
        YearMonth last = Collections.max(months);
        LocalDate from = first.atDay(1);
        LocalDate to = last.atEndOfMonth();
        return Optional.of(new Range(from, to, first.equals(last)
                ? "%s (%s to %s)".formatted(label(first), from, to)
                : "%s to %s (%s to %s)".formatted(label(first), label(last), from, to)));
    }

    private static Range trailing(LocalDate today, int days, String label) {
        return new Range(today.minusDays(days - 1L), today, label);
    }

    private static LocalDate quarterStart(LocalDate date) {
        int quarterMonth = ((date.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(date.getYear(), quarterMonth, 1);
    }

    private static String label(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
    }

    private static LocalDate date(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException e) {
            return null;
        }
    }
}
