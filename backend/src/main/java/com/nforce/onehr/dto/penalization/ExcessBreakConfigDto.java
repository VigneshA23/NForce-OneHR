package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

/**
 * PN-05: the Excess Break rule - total break time in one day above a limit. A day is an
 * occurrence when its breaks add up to more than {@code maxBreakMinutes}; the first
 * {@code exemptCount} occurrences per cycle are free, every later one deducts {@code deductionDays}.
 */
@Data
public class ExcessBreakConfigDto {

    private boolean enabled;

    @Min(value = 1, message = "Max break minutes must be at least 1")
    private Integer maxBreakMinutes;

    @Min(value = 0, message = "Exempt count cannot be negative")
    private Integer exemptCount;

    @Pattern(regexp = "WEEK|MONTH", message = "Exemption cycle must be WEEK or MONTH")
    private String exemptPeriod = "MONTH";

    @DecimalMin(value = "0", message = "Deduction days cannot be negative")
    private BigDecimal deductionDays;
}
