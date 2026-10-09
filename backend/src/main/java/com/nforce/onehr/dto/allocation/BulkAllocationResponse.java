package com.nforce.onehr.dto.allocation;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BulkAllocationResponse {

    private List<AllocationResult> results;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AllocationResult {
        private UUID employeeId;
        private String status;
        private String reason;

        public static AllocationResult success(UUID employeeId) {
            return AllocationResult.builder().employeeId(employeeId).status("SUCCESS").build();
        }

        public static AllocationResult failed(UUID employeeId, String reason) {
            return AllocationResult.builder().employeeId(employeeId).status("FAILED").reason(reason).build();
        }
    }
}
