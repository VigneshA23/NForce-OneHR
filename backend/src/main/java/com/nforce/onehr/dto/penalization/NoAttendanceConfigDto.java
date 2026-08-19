package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class NoAttendanceConfigDto {

    private boolean enabled;

    @DecimalMin(value = "0", message = "Deduction days cannot be negative")
    private BigDecimal deductionDays;

    /** "Employee working less than X Effective Hours will be considered as no show." */
    private boolean noShowEnabled;

    @DecimalMin(value = "0", message = "No-show threshold hours cannot be negative")
    private BigDecimal noShowThresholdHours;

    // ── Phase 2: adjoining-holiday / adjoining-week-off sandwich rules (Section 12-13) ──
    private boolean adjoiningHolidayEnabled;

    @Pattern(regexp = "SANDWICHED|BEFORE|AFTER|ANY", message = "Adjoining-holiday condition must be SANDWICHED, BEFORE, AFTER or ANY")
    private String adjoiningHolidayCondition;

    @Min(value = 1, message = "Calendar-day threshold must be at least 1")
    private Integer adjoiningHolidayCalendarDayThreshold;

    private boolean adjoiningHolidayIgnoreHalfDayLeave = true;

    private boolean adjoiningWeekoffEnabled;

    @Pattern(regexp = "SANDWICHED|BEFORE|AFTER|ANY", message = "Adjoining-week-off condition must be SANDWICHED, BEFORE, AFTER or ANY")
    private String adjoiningWeekoffCondition;

    @Min(value = 1, message = "Calendar-day threshold must be at least 1")
    private Integer adjoiningWeekoffCalendarDayThreshold;

    private boolean adjoiningWeekoffIgnoreHalfDayLeave = true;
}
