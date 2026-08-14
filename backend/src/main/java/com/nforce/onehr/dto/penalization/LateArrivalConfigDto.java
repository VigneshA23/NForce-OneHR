package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class LateArrivalConfigDto {

    private boolean enabled;

    /** Only one basis is confirmed by the approved screenshots — see class-level note in the service. */
    @Pattern(regexp = "NUMBER_OF_INCIDENTS", message = "Only 'number of incidents' is a supported basis")
    private String basis = "NUMBER_OF_INCIDENTS";

    @Min(value = 0, message = "Grace period cannot be negative")
    private Integer gracePeriodMinutes;

    @Min(value = 0, message = "Exempt count cannot be negative")
    private Integer exemptCount;

    @Pattern(regexp = "MONTH", message = "Only a monthly exemption cycle is supported")
    private String exemptPeriod = "MONTH";

    @DecimalMin(value = "0", message = "Deduction days cannot be negative")
    private BigDecimal deductionDays;

    @Min(value = 1, message = "Deduction-per-shifts must be at least 1")
    private Integer deductionPerShifts;

    /** "Ignore late arrival penalty when employee completes desired Effective Hours in a shift." */
    private boolean ignoreWhenEffectiveHoursMetEnabled;
}
