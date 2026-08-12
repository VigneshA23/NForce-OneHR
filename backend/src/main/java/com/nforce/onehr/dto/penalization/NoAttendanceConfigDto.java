package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.DecimalMin;
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
}
