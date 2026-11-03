package com.nforce.onehr.dto.assignments;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Shared request shape for all three bulk-update endpoints (shift/weekly-off/penalisation). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BulkAssignmentRequest {

    @NotEmpty(message = "At least one employee id is required")
    private List<UUID> employeeUserIds;

    @NotNull(message = "A policy id is required")
    private UUID policyId;

    // Required for shift (EmployeeAssignmentService#bulkUpdateShift) and, as of Phase 3, weekly-off
    // (#bulkUpdateWeeklyOff) assignment — today or any future date is accepted, a past date is
    // rejected. Still ignored by the penalisation-policy bulk endpoint, which remains
    // effective-today, open-ended, exactly as before — penalisation allocation has its own
    // separate effective-dating via PenalizationPolicyAllocationService, not this field.
    private LocalDate effectiveFrom;
}
