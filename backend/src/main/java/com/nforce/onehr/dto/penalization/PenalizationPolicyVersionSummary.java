package com.nforce.onehr.dto.penalization;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** One row of the version-history list (the "Version: Current - 2024-07-08 ▾" dropdown in the approved screenshots). */
@Data
@Builder
public class PenalizationPolicyVersionSummary {

    private UUID id;
    private Integer version;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private LocalDateTime createdAt;
}
