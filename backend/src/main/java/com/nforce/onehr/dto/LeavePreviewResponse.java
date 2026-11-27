package com.nforce.onehr.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * LV-07: what an unsaved leave request would take, for the apply form. {@code days}: each date looked at with
 * its kind (WORKING, SANDWICHED, WEEKLY_OFF, HOLIDAY — the last two not counted) and a note (holiday name).
 * {@code problem}: the first reason it would be refused (dates, a plan rule or the balance), else null; when the
 * dates themselves can't be counted, only {@code problem} is set.
 */
public record LeavePreviewResponse(BigDecimal totalDays, BigDecimal workingDays, BigDecimal sandwichedDays, String summary,
                                   Map<Integer, BigDecimal> daysByYear, List<Day> days, String problem) {

    public record Day(LocalDate date, String kind, BigDecimal days, String note) {}

    public static LeavePreviewResponse refused(String problem) {
        return new LeavePreviewResponse(null, null, null, null, Map.of(), List.of(), problem);
    }
}
