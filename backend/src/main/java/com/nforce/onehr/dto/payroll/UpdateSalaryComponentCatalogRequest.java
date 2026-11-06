package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Null on any field means "leave unchanged" — see SalaryComponentService#update. */
@Data
public class UpdateSalaryComponentCatalogRequest {

    @Size(max = 50)
    private String name;

    @Pattern(regexp = "EARNING|AD_HOC_EARNING|DEDUCTION|AD_HOC_DEDUCTION|REIMBURSEMENT",
            message = "type must be EARNING, AD_HOC_EARNING, DEDUCTION, AD_HOC_DEDUCTION or REIMBURSEMENT")
    private String type;

    private Boolean taxable;

    @Size(max = 50)
    private String taxSection;

    private Boolean includedInCtc;

    private Boolean shownOnPayslip;

    private Boolean prorateOnLop;

    private Boolean active;
}
