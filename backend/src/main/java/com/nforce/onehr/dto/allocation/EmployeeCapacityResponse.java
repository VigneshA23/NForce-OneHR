package com.nforce.onehr.dto.allocation;

import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** US-B05/US-B13: one employee's project allocations and total committed capacity. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeCapacityResponse {
    private UUID employeeId;
    private String employeeName;
    private UUID departmentId;
    private String departmentName;
    private int totalCapacityPercent;
    private List<AllocationSummary> allocations;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AllocationSummary {
        private UUID projectId;
        private String projectName;
        private Integer capacityPercent;
        private LocalDate startDate;
        private LocalDate endDate;
    }
}
