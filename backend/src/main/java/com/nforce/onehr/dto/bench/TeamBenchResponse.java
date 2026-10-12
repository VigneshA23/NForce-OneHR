package com.nforce.onehr.dto.bench;

import com.nforce.onehr.entity.BenchReasonCode;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

/** US-A02: one benched direct report as a Manager sees them. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeamBenchResponse {
    private UUID employeeId;
    private String employeeName;
    private BenchReasonCode reasonCode;
    private LocalDate startDate;
    private long daysOnBench;
    /** Most recently ended project allocation's name; null when the employee has never been allocated. */
    private String lastProject;
}
