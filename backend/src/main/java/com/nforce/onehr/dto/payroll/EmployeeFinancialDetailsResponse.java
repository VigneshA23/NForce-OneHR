package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

/** Keka restyle (payroll module) — "Import Financial Information" (§3.4). Unmasked, unlike
 * ProfileService's own self-service read (which masks to all-but-last-4): this is for a Payroll
 * Admin's edit/import view, where the real value has to be visible to correct it. */
@Data
@Builder
public class EmployeeFinancialDetailsResponse {
    private UUID employeeId;
    private String employeeCode;
    private String employeeName;
    private String bankAccountNumber;
    private String bankName;
    private String bankIfsc;
    private String accountHolderName;
    private String panNumber;
    private String uanNumber;
    private String esiNumber;
    private boolean pfEnabled;
    private String pfNumber;
    private LocalDate pfRegistrationDate;
    private String nameAsPerPf;
    private boolean esiEnabled;
    private String professionalTaxNumber;
}
