package com.nforce.onehr.dto.bench;

import com.nforce.onehr.entity.BenchCloseReason;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * US-A06: end a bench episode. endDate defaults to today. Either allocationId (the employee was
 * allocated to a project) or closeReason is required.
 */
@Getter @Setter
public class EndBenchRequest {
    private LocalDate endDate;
    private UUID allocationId;
    private BenchCloseReason closeReason;
    private String closeNote;
}
