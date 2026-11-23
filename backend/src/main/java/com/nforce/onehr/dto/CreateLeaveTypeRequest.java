package com.nforce.onehr.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateLeaveTypeRequest {

    @NotBlank
    @Size(max = 20)
    @Pattern(regexp = "[A-Za-z0-9_]+", message = "code may contain only letters, digits and underscores")
    private String code;

    @NotBlank
    @Size(max = 50)
    private String name;

    @NotBlank
    @Pattern(regexp = "PAID|UNPAID", message = "classification must be PAID or UNPAID")
    private String classification;

    /** Paid only; defaults to ANNUAL. */
    @Pattern(regexp = "ANNUAL|PER_OCCURRENCE", message = "entitlementBasis must be ANNUAL or PER_OCCURRENCE")
    private String entitlementBasis;

    /** Required for paid types: days per leave year (ANNUAL) or per occurrence (PER_OCCURRENCE). */
    @DecimalMin("0")
    @DecimalMax("999")
    private BigDecimal entitlementDays;

    /** Required for PER_OCCURRENCE. */
    @Min(1)
    @Max(120)
    private Integer occurrenceWindowMonths;
}
