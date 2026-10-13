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
}
