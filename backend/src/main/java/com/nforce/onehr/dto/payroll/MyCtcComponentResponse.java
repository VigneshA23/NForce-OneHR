package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class MyCtcComponentResponse {
    private String componentName;
    private String componentType;
    private BigDecimal annualAmount;
    private BigDecimal monthlyAmount;
}
