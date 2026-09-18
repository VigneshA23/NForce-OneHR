package com.nforce.onehr.dto.attendance;

import lombok.*;

/** One side of the Me-vs-My-Team comparison — see AttendanceStatsResponse. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceStatBucket {

    private int presentDays;

    /** Null when presentDays == 0, or when every present row is still mid-session (no
     * workedMinutes computed yet) — nothing to average. */
    private Double avgHoursPerDay;

    /** Null when presentDays == 0. */
    private Double onTimeArrivalPercent;

    /**
     * Average expected work hours per working day over the requested range — the assigned
     * shift's duration, reduced by any approved hourly/quarter-day leave on that date (see
     * ExpectedWorkHoursService), the same calculation the Penalization Policy engine uses. Null
     * when there were no working days in range or no employee had an assigned shift to compute
     * it from — never independently recalculated for this display.
     */
    private Double expectedHoursPerDay;
}
