package com.nforce.onehr.dto.bench;

import com.nforce.onehr.entity.BenchCloseReason;
import com.nforce.onehr.entity.BenchReasonCode;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BenchStatusResponse {
    private UUID id;
    private UUID employeeId;
    private String employeeName;
    private BenchReasonCode reasonCode;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean active;
    private String agingBucket;
    private BenchCloseReason closeReason;
    private UUID allocationId;
}
