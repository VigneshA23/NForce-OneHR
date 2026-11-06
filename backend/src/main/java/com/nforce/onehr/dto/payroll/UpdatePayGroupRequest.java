package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Null on any field means "leave unchanged" — see PayGroupService#update. */
@Data
public class UpdatePayGroupRequest {

    @Size(max = 100)
    private String name;

    @Size(max = 100)
    private String legalEntity;

    @Pattern(regexp = "MONTHLY", message = "frequency must be MONTHLY")
    private String frequency;

    @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO 4217 code")
    private String currency;

    @Min(1) @Max(31)
    private Integer cycleStartDay;

    @Min(1) @Max(31)
    private Integer cycleEndDay;

    @Min(1) @Max(31)
    private Integer processingDay;

    private Boolean active;
}
