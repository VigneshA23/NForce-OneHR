package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
public class PayslipLineItemResponse {
    private UUID componentId;
    private String componentName;
    private String componentType;
    private BigDecimal amount;
    private boolean prorated;
}
