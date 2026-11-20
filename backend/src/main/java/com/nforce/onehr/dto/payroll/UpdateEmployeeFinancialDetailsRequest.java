package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** Null on any field means "leave unchanged" — same convention as ProfileService#update and
 * UpdateSalaryComponentCatalogRequest. {@code pfEnabled}/{@code esiEnabled} are booleans, so they
 * can't use that null-means-unchanged convention — they're always applied when the request is
 * submitted (the edit form always knows and sends their current/intended value). */
@Data
public class UpdateEmployeeFinancialDetailsRequest {

    @Size(max = 50)
    private String bankAccountNumber;

    @Size(max = 150)
    private String bankName;

    @Size(max = 20)
    private String bankIfsc;

    @Size(max = 150)
    private String accountHolderName;

    @Size(max = 20)
    private String panNumber;

    @Size(max = 20)
    private String uanNumber;

    @Size(max = 20)
    private String esiNumber;

    private boolean pfEnabled;

    @Size(max = 30)
    private String pfNumber;

    private LocalDate pfRegistrationDate;

    @Size(max = 150)
    private String nameAsPerPf;

    private boolean esiEnabled;

    @Size(max = 30)
    private String professionalTaxNumber;
}
