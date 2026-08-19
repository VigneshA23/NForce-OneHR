package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** One row of the Work Hours Shortage tiered deduction table — "less than X% of shift hours → Y day(s)". */
@Data
public class WorkHoursTierDto {

    @NotNull
    @DecimalMin(value = "0", message = "Threshold percent cannot be negative")
    private BigDecimal thresholdPercent;

    @NotNull
    @DecimalMin(value = "0", message = "Deduction days cannot be negative")
    private BigDecimal deductionDays;
}
