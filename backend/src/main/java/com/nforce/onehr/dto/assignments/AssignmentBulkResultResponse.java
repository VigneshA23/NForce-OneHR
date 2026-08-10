package com.nforce.onehr.dto.assignments;

import lombok.*;

import java.util.List;
import java.util.UUID;

/** Per-row outcome of a bulk assignment update — one employee's failure never blocks another's. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssignmentBulkResultResponse {

    private List<UUID> succeededIds;
    private List<FailureDto> failed;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class FailureDto {
        private UUID employeeUserId;
        private String reason;
    }
}
