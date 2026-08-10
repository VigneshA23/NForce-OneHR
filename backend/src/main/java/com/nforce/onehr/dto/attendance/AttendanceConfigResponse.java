package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalTime;
import java.util.List;

/**
 * Read-only shift/break config for the frontend's Today's Timings panel, resolved per-caller:
 * shiftStart/shiftEnd come from the caller's assigned Shift (ONEHR-108) if one exists, else fall
 * back to the global AttendanceProperties.shiftStart with a null shiftEnd (no global end exists).
 * weeklyOffDays similarly comes from the caller's assigned WeeklyOffPolicy, else defaults to
 * Saturday/Sunday. See AttendanceService.getConfig().
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AttendanceConfigResponse {

    private LocalTime shiftStart;
    /** Null only if the caller has no Shift assigned — every employee is seeded with one (V95), so this is normally always set. */
    private LocalTime shiftEnd;
    private int lateGraceMinutes;
    private int halfDayMaxHours;
    private int fullDayMinHours;
    private int dailyBreakBudgetMinutes;
    /** java.time.DayOfWeek names, e.g. ["SATURDAY", "SUNDAY"]. */
    private List<String> weeklyOffDays;
}
