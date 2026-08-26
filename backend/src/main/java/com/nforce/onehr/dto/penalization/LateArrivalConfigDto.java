package com.nforce.onehr.dto.penalization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class LateArrivalConfigDto {

    private boolean enabled;

    @Pattern(regexp = "NUMBER_OF_INCIDENTS|TOTAL_HOURS", message = "Basis must be NUMBER_OF_INCIDENTS or TOTAL_HOURS")
    private String basis = "NUMBER_OF_INCIDENTS";

    @Min(value = 0, message = "Grace period cannot be negative")
    private Integer gracePeriodMinutes;

    @Min(value = 0, message = "Exempt count cannot be negative")
    private Integer exemptCount;

    @Pattern(regexp = "WEEK|MONTH", message = "Exemption cycle must be WEEK or MONTH")
    private String exemptPeriod = "MONTH";

    @DecimalMin(value = "0", message = "Deduction days cannot be negative")
    private BigDecimal deductionDays;

    @Min(value = 1, message = "Deduction-per-shifts must be at least 1")
    private Integer deductionPerShifts;

    /** "Ignore late arrival penalty when employee completes desired Effective Hours in a shift." */
    private boolean ignoreWhenEffectiveHoursMetEnabled;

    // ── Phase 2: Total Hours basis (Section 25/29/31) ──
    /** "Allowed X hours per [exemptPeriod] cycle" — only meaningful when basis is TOTAL_HOURS. */
    @DecimalMin(value = "0", message = "Allowed hours cannot be negative")
    private BigDecimal allowedHours;

    /** "Total Late Hours in Shift | Leave Deduction" tiered rule table. */
    @Valid
    private List<LateHoursTierDto> lateHoursTiers = List.of();

    @Pattern(regexp = "TOTAL_HOURS_ONLY|BOTH", message = "Combined-rule behavior must be TOTAL_HOURS_ONLY or BOTH")
    private String combinedRuleBehavior = "TOTAL_HOURS_ONLY";

    /** "Penalise any late arrival caused by missing logs." */
    private boolean penaliseWhenCausedByMissingLogEnabled;
}
