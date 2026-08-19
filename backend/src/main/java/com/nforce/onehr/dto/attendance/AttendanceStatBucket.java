package com.nforce.onehr.dto.attendance;

import lombok.*;

/** One side of the Me-vs-My-Team comparison — see AttendanceStatsResponse. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceStatBucket {

    private int presentDays;

    /** Null when presentDays == 0 — nothing to average. */
    private Double avgHoursPerDay;

    /** Null when presentDays == 0. */
    private Double onTimeArrivalPercent;
}
