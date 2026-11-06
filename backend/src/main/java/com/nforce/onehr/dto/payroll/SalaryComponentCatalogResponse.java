package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class SalaryComponentCatalogResponse {
    private UUID id;
    private String name;
    private String type;
    private boolean taxable;
    private String taxSection;
    private boolean includedInCtc;
    private boolean shownOnPayslip;
    private boolean prorateOnLop;
    private boolean active;
}
