package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;

/**
 * PN-03: per-policy early-warning settings - tell the employee they are one step from a penalty.
 * {@code enabled} is the master switch (off by default); each rule can also be toggled on its own.
 */
@Data
public class EarlyWarningConfigDto {

    private boolean enabled;
    private boolean lateArrivalEnabled = true;
    private boolean missingLogsEnabled = true;
    private boolean shortageEnabled = true;

    /** Warn when this many (or fewer) exempt late marks / missing-log days remain. 1 = limit minus 1. */
    @Min(value = 1, message = "Remaining exempt count must be at least 1")
    @Max(value = 10, message = "Remaining exempt count cannot exceed 10")
    private int exemptRemaining = 1;

    /** Warn when the period's work-hours percentage is within this many points above a shortage tier. */
    @DecimalMin(value = "1", message = "Shortage margin must be at least 1 percentage point")
    @DecimalMax(value = "50", message = "Shortage margin cannot exceed 50 percentage points")
    private BigDecimal shortageMarginPercent = BigDecimal.TEN;
}
