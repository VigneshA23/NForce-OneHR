package com.nforce.onehr.dto.penalization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class WorkHoursShortageConfigDto {

    private boolean enabled;

    @Pattern(regexp = "EFFECTIVE_HOURS|GROSS_HOURS", message = "Deduction basis must be EFFECTIVE_HOURS or GROSS_HOURS")
    private String deductionBasis = "EFFECTIVE_HOURS";

    @Pattern(regexp = "DAY|WEEK|MONTH", message = "Deduction period must be DAY, WEEK or MONTH")
    private String deductionPeriod = "DAY";

    @Valid
    private List<WorkHoursTierDto> tiers = List.of();

    /** "When shortage and late arrival both occur the same day" — same-day interaction settings. */
    private boolean applyPenaltyForShortageEnabled = true;
    private boolean applyPenaltyForLateArrivalEnabled;

    /**
     * "Exclude hours worked outside the assigned shift timing" — e.g. a 09:00-18:00 shift with an
     * 08:00-18:00 punch counts only the 9 shift hours toward shortage when enabled, all 10 when
     * disabled (the default, preserving prior behavior).
     */
    private boolean excludeHoursOutsideShiftEnabled;

    /**
     * "Penalize shortage caused by missing logs" — when enabled, a day whose own check-out is
     * missing is treated as a candidate shortage day (0 worked minutes) instead of being skipped
     * entirely, and (for WEEK/MONTH) contributes 0 to the cycle's worked total rather than being
     * excluded from it. Default false preserves the original behavior of never evaluating Work
     * Hours Shortage for a day with an unresolved missing log.
     */
    private boolean penalizeShortageCausedByMissingLogsEnabled;
}
