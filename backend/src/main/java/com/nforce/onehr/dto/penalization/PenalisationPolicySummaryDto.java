package com.nforce.onehr.dto.penalization;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** One row of the Policy List (Section 5): Policy Name, Status, Employee Count, Version, Effective Date. */
@Data
@Builder
public class PenalisationPolicySummaryDto {

    private UUID id;
    private String name;
    private String description;
    private String status;
    private long employeeCount;
    /** Section 7: the org-wide fallback for an employee with no allocation and no legacy FK. */
    private boolean orgDefault;

    /** Null when this policy has never had a rule configuration saved yet. */
    private Integer currentVersion;
    private java.time.LocalDateTime effectiveFrom;

    private UUID createdBy;
    private LocalDateTime createdAt;
}
