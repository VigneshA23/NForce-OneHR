package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class MyCtcBreakdownResponse {
    private UUID structureId;
    private String structureName;
    private BigDecimal ctc;
    private LocalDate effectiveFrom;
    private List<MyCtcComponentResponse> components;
}
