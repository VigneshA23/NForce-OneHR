package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * One line of a {@link CreateSalaryStructureRequest}: "use catalog component X, computed this
 * way in this structure." Cross-field rules that can't be expressed as bean validation
 * (componentName must resolve to an active catalog entry, value required unless
 * BALANCING_REMAINDER, referenceComponentName required iff PERCENT_OF_COMPONENT, useLowerOfCap
 * requires cap) are enforced in {@link com.nforce.onehr.service.SalaryStructureService}.
 */
@Data
public class SalaryComponentRequest {

    // Must match an existing, active SalaryComponent catalog entry's name (see US-C20) —
    // resolved at create time, not stored as free text.
    @NotBlank
    @Size(max = 50)
    private String componentName;

    @NotBlank
    @Pattern(regexp = "FIXED|PERCENT_OF_GROSS|PERCENT_OF_COMPONENT|BALANCING_REMAINDER",
            message = "calcType must be FIXED, PERCENT_OF_GROSS, PERCENT_OF_COMPONENT or BALANCING_REMAINDER")
    private String calcType;

    // Required unless calcType is BALANCING_REMAINDER (that component has no stored value).
    @DecimalMin(value = "0", message = "value cannot be negative")
    private BigDecimal value;

    // Required, and only meaningful, when calcType is PERCENT_OF_COMPONENT — the componentName of
    // another entry in this SAME request (e.g. "Basic"), resolved to that sibling's structure-
    // component id once every line in the request has been saved (see
    // SalaryStructureService#create).
    @Size(max = 50)
    private String referenceComponentName;

    @DecimalMin(value = "0", message = "cap cannot be negative")
    private BigDecimal cap;

    private boolean useLowerOfCap = false;
}
