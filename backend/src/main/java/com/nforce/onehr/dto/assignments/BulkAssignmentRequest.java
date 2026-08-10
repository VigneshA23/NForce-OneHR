package com.nforce.onehr.dto.assignments;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;
import java.util.UUID;

/** Shared request shape for all three bulk-update endpoints (shift/weekly-off/penalisation). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BulkAssignmentRequest {

    @NotEmpty(message = "At least one employee id is required")
    private List<UUID> employeeUserIds;

    @NotNull(message = "A policy id is required")
    private UUID policyId;
}
