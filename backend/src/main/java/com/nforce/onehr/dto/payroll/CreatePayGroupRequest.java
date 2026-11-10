package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreatePayGroupRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 100)
    private String legalEntity;

    @Pattern(regexp = "MONTHLY", message = "frequency must be MONTHLY")
    private String frequency = "MONTHLY";

    @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO 4217 code")
    private String currency = "INR";

    @Min(1) @Max(31)
    private int cycleStartDay;

    @Min(1) @Max(31)
    private int cycleEndDay;

    @Min(1) @Max(31)
    private int processingDay;
}
