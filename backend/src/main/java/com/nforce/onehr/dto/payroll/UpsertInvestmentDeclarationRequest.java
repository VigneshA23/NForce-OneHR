package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/** Create-or-update by the (employeeId, financialYear, section) triple — same "Add/Update"
 * semantics as Keka's own wording for this import. */
@Data
public class UpsertInvestmentDeclarationRequest {

    @NotNull
    private UUID employeeId;

    @NotBlank
    @Size(max = 10)
    private String financialYear;

    @NotBlank
    @Size(max = 30)
    private String section;

    @NotNull
    @DecimalMin(value = "0", message = "declaredAmount must not be negative")
    private BigDecimal declaredAmount;
}
