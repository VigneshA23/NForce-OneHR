package com.nforce.onehr.dto.penalization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

@Data
public class WorkHoursShortageConfigDto {

    private boolean enabled;

    @Pattern(regexp = "EFFECTIVE_HOURS", message = "Only 'effective hours' is a supported basis")
    private String deductionBasis = "EFFECTIVE_HOURS";

    @Pattern(regexp = "DAY", message = "Only a daily deduction period is supported")
    private String deductionPeriod = "DAY";

    @Valid
    private List<WorkHoursTierDto> tiers = List.of();

    /** "When shortage and late arrival both occur the same day" — same-day interaction settings. */
    private boolean applyPenaltyForShortageEnabled = true;
    private boolean applyPenaltyForLateArrivalEnabled;
}
