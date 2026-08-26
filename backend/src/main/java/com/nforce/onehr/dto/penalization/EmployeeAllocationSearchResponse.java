package com.nforce.onehr.dto.penalization;

import lombok.*;

import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeAllocationSearchResponse {
    private List<EmployeeAllocationRow> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;
}
