package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/** One SalaryStructureComponent, with its catalog entry's descriptive fields inlined for display. */
@Data
@Builder
public class SalaryComponentResponse {
    private UUID id;
    private UUID componentId;
    private String componentName;
    private String type;
    private boolean taxable;
    private boolean includedInCtc;
    private boolean shownOnPayslip;
    private boolean prorateOnLop;
    private String calcType;
    private BigDecimal value;
    // Resolved back from referenceStructureComponentId for readability — null unless calcType is
    // PERCENT_OF_COMPONENT.
    private String referenceComponentName;
    private BigDecimal cap;
    private boolean useLowerOfCap;
}
