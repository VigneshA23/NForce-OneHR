package com.nforce.onehr.dto.attendance;

import lombok.*;

/** Computed from applicable daily values only (i.e. only dates that were a working day for someone). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PunctualitySummary {

    private double averageEmployeesOnTime;
    private int minimumEmployeesOnTime;
    private int maximumEmployeesOnTime;
}
