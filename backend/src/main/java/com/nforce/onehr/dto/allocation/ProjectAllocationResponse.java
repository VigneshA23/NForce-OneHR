package com.nforce.onehr.dto.allocation;

import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProjectAllocationResponse {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private UUID projectId;
    private String projectName;
    private Integer capacityPercent;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate actualEndDate;
    /** US-B06: employee's total capacity% over the range including this allocation. */
    private Integer projectedCapacityPercent;
    /** US-B06: set when projected > 100%. With id == null nothing was saved — resubmit with confirm=true. */
    private String warning;
}
