package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSalaryComponentCatalogRequest {

    @NotBlank
    @Size(max = 50)
    private String name;

    @NotBlank
    @Pattern(regexp = "EARNING|AD_HOC_EARNING|DEDUCTION|AD_HOC_DEDUCTION|REIMBURSEMENT",
            message = "type must be EARNING, AD_HOC_EARNING, DEDUCTION, AD_HOC_DEDUCTION or REIMBURSEMENT")
    private String type;

    private boolean taxable = true;

    @Size(max = 50)
    private String taxSection;

    private boolean includedInCtc = true;

    private boolean shownOnPayslip = true;

    private boolean prorateOnLop = true;
}
