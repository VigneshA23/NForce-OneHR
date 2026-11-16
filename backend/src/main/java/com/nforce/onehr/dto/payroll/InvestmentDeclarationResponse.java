package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class InvestmentDeclarationResponse {
    private UUID id;
    private UUID employeeId;
    private String financialYear;
    private String section;
    private BigDecimal declaredAmount;
}
