package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Null on any field means "leave unchanged" — see SalaryComponentService#update. */
@Data
public class UpdateSalaryComponentCatalogRequest {

    @Size(max = 50)
    private String name;

    @Pattern(regexp = "EARNING|AD_HOC_EARNING|DEDUCTION|AD_HOC_DEDUCTION|REIMBURSEMENT|CONTRIBUTION",
            message = "type must be EARNING, AD_HOC_EARNING, DEDUCTION, AD_HOC_DEDUCTION, REIMBURSEMENT or CONTRIBUTION")
    private String type;

    private Boolean taxable;

    @Size(max = 50)
    private String taxSection;

    private Boolean includedInCtc;

    private Boolean shownOnPayslip;

    private Boolean prorateOnLop;

    private Boolean active;

    // displayOrder is deliberately NOT here — it's set only via the dedicated reorder endpoint
    // (SalaryComponentService#reorder), which assigns every row's position in one transaction;
    // exposing it here would let two concurrent "update this one field" calls race each other's
    // positions.
    @Size(max = 30)
    private String accountingCode;
}
