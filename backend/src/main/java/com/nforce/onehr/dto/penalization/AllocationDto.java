package com.nforce.onehr.dto.penalization;

import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** One {@code PenalizationPolicyAllocation} row — used both in an employee's allocation history and inline in search rows. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllocationDto {
    private UUID id;
    private UUID penalisationPolicyId;
    private String penalisationPolicyName;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    /** CURRENT / FUTURE / HISTORICAL, derived against today at read time — never stored. */
    private String status;
    private UUID createdBy;
    private LocalDateTime createdAt;
    private UUID updatedBy;
    private LocalDateTime updatedAt;
}
