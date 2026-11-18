package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Keka restyle — "Current Salary Structure" report (§3.6): the org-wide version of {@link
 * MyCtcBreakdownResponse} — same {@code SalaryStructureResolutionService} engine, one row per
 * employee instead of "me" resolved from the JWT. */
@Getter
@Builder
public class CurrentSalaryStructureRowResponse {
    private UUID employeeId;
    private UUID structureId;
    private String structureName;
    private BigDecimal ctc;
    private LocalDate effectiveFrom;
    private List<MyCtcComponentResponse> components;
}
