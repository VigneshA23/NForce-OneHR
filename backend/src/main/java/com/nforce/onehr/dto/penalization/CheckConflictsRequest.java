package com.nforce.onehr.dto.penalization;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Gap-016: pre-submit preview of the same overlap check {@code allocate}/{@code bulkAllocate} enforce at write time. */
@Data
public class CheckConflictsRequest {
    @NotEmpty
    private List<UUID> employeeUserIds;
    @NotNull
    private LocalDate effectiveFrom;
    /** Null = no end date (open-ended). */
    private LocalDate effectiveTo;
    /** Set when previewing an edit to an existing allocation, so that row doesn't conflict with itself. */
    private UUID excludeAllocationId;
}
