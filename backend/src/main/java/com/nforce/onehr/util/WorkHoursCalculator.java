package com.nforce.onehr.util;

/**
 * The one place worked-minutes/expected-minutes are converted to and from a percentage — used by
 * ExceptionService's Work Hours Shortage/Missing Logs fact-gathering (percent-of-expected-hours),
 * and available to any future consumer (Attendance Summary, dashboards) that needs the same
 * conversion. Previously duplicated inline as {@code workedMinutes * 100.0 / shiftMinutes} — see
 * ExceptionService's history — this class exists precisely so that formula is written once.
 *
 * <p>Both directions return {@code null} (never 0/0.0) when a required input is missing or the
 * expected-minutes denominator isn't positive, so callers can tell "no data" apart from a genuine
 * 0% or 0-minute result — same convention as PolicyEvaluationContext's other nullable facts.
 */
public final class WorkHoursCalculator {

    private WorkHoursCalculator() {}

    /** {@code minutes} as a percentage of {@code expectedMinutes} — e.g. 240 worked of 480 expected -> 50.0. */
    public static Double minutesToPercent(Integer minutes, Long expectedMinutes) {
        return minutes == null ? null : minutesToPercent((long) (int) minutes, expectedMinutes);
    }

    /** Same as {@link #minutesToPercent(Integer, Long)} — a {@code Long} minutes overload for cycle-aggregated (weekly/monthly) totals that can exceed {@code Integer} range in principle. */
    public static Double minutesToPercent(Long minutes, Long expectedMinutes) {
        if (minutes == null || expectedMinutes == null || expectedMinutes <= 0) {
            return null;
        }
        return minutes * 100.0 / expectedMinutes;
    }

    /** The inverse of {@link #minutesToPercent} — e.g. 50.0% of 480 expected minutes -> 240. */
    public static Long percentToMinutes(Double percent, Long expectedMinutes) {
        if (percent == null || expectedMinutes == null || expectedMinutes <= 0) {
            return null;
        }
        return Math.round(expectedMinutes * (percent / 100.0));
    }
}
