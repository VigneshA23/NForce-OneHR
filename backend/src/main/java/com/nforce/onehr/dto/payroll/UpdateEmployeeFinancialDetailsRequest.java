package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Null on any field means "leave unchanged" — same convention as ProfileService#update and
 * UpdateSalaryComponentCatalogRequest. */
@Data
public class UpdateEmployeeFinancialDetailsRequest {

    @Size(max = 50)
    private String bankAccountNumber;

    @Size(max = 150)
    private String bankName;

    @Size(max = 20)
    private String bankIfsc;

    @Size(max = 20)
    private String panNumber;

    @Size(max = 20)
    private String uanNumber;

    @Size(max = 20)
    private String esiNumber;
}
