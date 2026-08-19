package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MissingLogsConfigDto {

    private boolean enabled;

    @Min(value = 0, message = "Exempt days cannot be negative")
    private Integer exemptDays;

    @Pattern(regexp = "WEEK|MONTH", message = "Exemption cycle must be WEEK or MONTH")
    private String exemptPeriod = "MONTH";

    @Pattern(regexp = "PER_SHIFT|IRRESPECTIVE", message = "Deduction mode must be PER_SHIFT or IRRESPECTIVE")
    private String deductionMode = "PER_SHIFT";

    @DecimalMin(value = "0", message = "Deduction days cannot be negative")
    private BigDecimal deductionDays;

    @Min(value = 1, message = "Deduction-per-shifts must be at least 1")
    private Integer deductionPerShifts;

    /** "Ignore missing logs rule when Effective Hours are greater than X% of shift hours." */
    private boolean ignoreRuleEnabled;

    @DecimalMin(value = "0", message = "Ignore-rule threshold percent cannot be negative")
    private BigDecimal ignoreRuleThresholdPercent;
}
