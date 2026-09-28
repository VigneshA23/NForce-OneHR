package com.nforce.onehr.ai.data;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a natural date expression in the raw question - "today", "this week", "last 7 days",
 * "last 30 days", "this month", "last month", "this quarter", "from 2026-09-01 to 2026-09-15" -
 * into a concrete range, anchored to OneHR's own business date.
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
    /** Only ISO dates: an unambiguous explicit range is worth honouring, a guessed one is not. */
    private static final Pattern ISO_RANGE = Pattern.compile(
            "\\b(\\d{4}-\\d{2}-\\d{2})\\s*(?:to|and|-|–|through|thru)\\s*(\\d{4}-\\d{2}-\\d{2})\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * @param question    the raw user question, never null-checked for content - only matched against
     * @param today       the org's business-zone "today" (see {@code AttendanceRulesService})
     * @param fallbackDays the trailing window size to fall back to when nothing recognisable matched
     */
    public static Range resolve(String question, LocalDate today, int fallbackDays) {
        String q = question == null ? "" : question;

        Matcher iso = ISO_RANGE.matcher(q);
        if (iso.find()) {
            LocalDate from = tryParse(iso.group(1));
            LocalDate to = tryParse(iso.group(2));
            if (from != null && to != null && !from.isAfter(to)) {
                // Never later than business-today, and never a range so wide it stops being "the
                // period asked about" - capped at today, exactly like the screen's own date inputs
                // (DateRangeControl's `to` input has max=todayIsoDate()).
                LocalDate cappedTo = to.isAfter(today) ? today : to;
                if (!from.isAfter(cappedTo)) {
                    return new Range(from, cappedTo, from + " to " + cappedTo);
                }
            }
        }
        if (TODAY.matcher(q).find()) {
            return new Range(today, today, "today");
        }
        if (YESTERDAY.matcher(q).find()) {
            LocalDate y = today.minusDays(1);
            return new Range(y, y, "yesterday");
        }
        if (THIS_WEEK.matcher(q).find()) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new Range(monday, today, "this week (" + monday + " to " + today + ")");
        }
        if (LAST_WEEK_ROLLING.matcher(q).find()) {
            return trailing(today, 7, "the last 7 days");
        }
        Matcher lastN = LAST_N_DAYS.matcher(q);
        if (lastN.find()) {
            int days = Integer.parseInt(lastN.group(1));
            if (days >= 1 && days <= 366) {
                return trailing(today, days, "the last " + days + " days");
            }
        }
        if (THIS_MONTH.matcher(q).find()) {
            LocalDate first = today.withDayOfMonth(1);
            return new Range(first, today, "this month (" + first + " to " + today + ")");
        }
        if (LAST_MONTH.matcher(q).find()) {
            LocalDate firstOfThisMonth = today.withDayOfMonth(1);
            LocalDate lastMonthEnd = firstOfThisMonth.minusDays(1);
            LocalDate lastMonthStart = lastMonthEnd.withDayOfMonth(1);
            return new Range(lastMonthStart, lastMonthEnd, "last month (" + lastMonthStart + " to " + lastMonthEnd + ")");
        }
        if (THIS_QUARTER.matcher(q).find()) {
            LocalDate start = quarterStart(today);
            return new Range(start, today, "this quarter (" + start + " to " + today + ")");
        }
        if (LAST_QUARTER.matcher(q).find()) {
            LocalDate thisQuarterStart = quarterStart(today);
            LocalDate lastQuarterEnd = thisQuarterStart.minusDays(1);
            LocalDate lastQuarterStart = quarterStart(lastQuarterEnd);
            return new Range(lastQuarterStart, lastQuarterEnd, "last quarter (" + lastQuarterStart + " to " + lastQuarterEnd + ")");
        }
        return trailing(today, fallbackDays, "the last " + fallbackDays + " days (no period was specified)");
    }

    private static Range trailing(LocalDate today, int days, String label) {
        return new Range(today.minusDays(days - 1L), today, label);
    }

    private static LocalDate quarterStart(LocalDate date) {
        int quarterMonth = ((date.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(date.getYear(), quarterMonth, 1);
    }

    private static LocalDate tryParse(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
