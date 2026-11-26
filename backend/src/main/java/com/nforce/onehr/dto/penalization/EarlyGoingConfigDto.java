package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

/**
 * PN-05: the Early Going rule - leaving before the shift's scheduled end. Same shape as
 * {@link LateArrivalConfigDto}'s incident basis: a grace window, an exempt count per cycle, then a
 * deduction on every Nth occurrence past the exempt count.
 */
@Data
public class EarlyGoingConfigDto {

    private boolean enabled;

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
}
