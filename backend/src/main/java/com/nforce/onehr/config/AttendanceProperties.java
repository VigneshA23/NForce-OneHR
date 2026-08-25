package com.nforce.onehr.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * Shift rules used to derive attendance status. Values are configurable so the PO can tune
 * them without a code change — the thresholds in the defaults below are provisional and
 * pending final confirmation.
 */
@Component
@ConfigurationProperties(prefix = "app.attendance")
@Getter @Setter
public class AttendanceProperties {

    /**
     * Business timezone. Drives both the recorded punch time and the work_date the punch is
     * attributed to. Must NOT be left to the JVM default — Railway runs UTC, which would roll
     * the work date over at 05:30 IST and break the one-pair-per-day rule at shift boundaries.
     */
    private String zone = "Asia/Kolkata";

    /** Scheduled start of the workday. Shift is 3:30 PM - 12:30 AM — crosses midnight. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime shiftStart = LocalTime.of(15, 30);

    /**
     * Shift-day cutover — the boundary at which a fresh check-in (or any other "what shift-day
     * is this?" attribution) stops belonging to the previous shift-day and starts belonging to
     * today's. The shift ends at 12:30 AM, but a fresh punch anywhere from midnight up to this
     * time still belongs to the shift that started the evening before; only from this time
     * onward does a fresh punch start a new shift-day. See AttendanceService.shiftDayOf.
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime shiftDayCutover = LocalTime.of(7, 0);

    /** Minutes past shiftStart that are forgiven before a punch counts as LATE. */
    private int lateGraceMinutes = 10;

    // Fractional hours (3.5 = 3h30m) — was `int` (whole-hour-only) until the business rule was
    // confirmed as 3h30m/7h30m; `double` is exact for both here (.5 has no floating-point
    // rounding error), and every call site already does `getHalfDayMaxHours() * 60` /
    // `getFullDayMinHours() * 60`, which stays correct unchanged (double * int => double,
    // compared against an int workedMinutes via normal widening).

    /** A day with fewer worked hours than this is HALF_DAY. */
    private double halfDayMaxHours = 3.5;

    /** Worked hours required for the day to count as a full day. */
    private double fullDayMinHours = 7.5;

    /** Daily break allowance before it starts eating into worked hours. Provisional — pending PO/final confirmation. */
    private int dailyBreakBudgetMinutes = 60;
}
