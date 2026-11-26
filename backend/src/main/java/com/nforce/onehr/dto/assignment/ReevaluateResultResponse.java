package com.nforce.onehr.dto.assignment;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReevaluateResultResponse {
    private String domain;
    /** How many employees' recorded outcome changed. */
    private int changed;
}
