package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** One row of the Late Arrival section's "Total Late Hours in Shift" tiered deduction table. */
@Data
public class LateHoursTierDto {

    @NotNull
    @DecimalMin(value = "0", message = "Threshold hours cannot be negative")
    private BigDecimal thresholdHours;

    @NotNull
    @DecimalMin(value = "0", message = "Deduction days cannot be negative")
    private BigDecimal deductionDays;
}
