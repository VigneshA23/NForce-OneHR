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

    /** Scheduled start of the workday. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
    private LocalTime shiftStart = LocalTime.of(9, 30);

    /** Minutes past shiftStart that are forgiven before a punch counts as LATE. */
    private int lateGraceMinutes = 15;

    /** A day with fewer worked hours than this is HALF_DAY. */
    private int halfDayMaxHours = 4;

    /** Worked hours required for the day to count as a full day. */
    private int fullDayMinHours = 8;
}
