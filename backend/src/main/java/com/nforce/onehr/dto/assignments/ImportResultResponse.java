package com.nforce.onehr.dto.assignments;

import lombok.*;

import java.util.List;

/** Per-row outcome of the "Import Shifts & Weekly Offs" CSV upload (ONEHR-108). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ImportResultResponse {

    private int totalRows;
    private int succeeded;
    private int failed;
    private List<RowResult> results;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class RowResult {
        private int row;
        private String employeeCode;
        private boolean success;
        private String error;
    }
}
