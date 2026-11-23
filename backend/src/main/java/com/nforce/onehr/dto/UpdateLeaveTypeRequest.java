package com.nforce.onehr.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateLeaveTypeRequest {

    @Size(max = 50)
    private String name;

    @Pattern(regexp = "PAID|UNPAID", message = "classification must be PAID or UNPAID")
    private String classification;

    /** Only changeable while nothing references the type. */
    @Pattern(regexp = "ANNUAL|PER_OCCURRENCE", message = "entitlementBasis must be ANNUAL or PER_OCCURRENCE")
    private String entitlementBasis;

    @Min(1)
    @Max(120)
    private Integer occurrenceWindowMonths;

    /** Used only when an unpaid type becomes paid and has no entitlement policy yet. */
    @DecimalMin("0")
    @DecimalMax("999")
    private BigDecimal entitlementDays;
}
