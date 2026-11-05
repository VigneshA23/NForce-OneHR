package com.nforce.onehr.dto.payroll;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class SalaryStructureResponse {
    private UUID id;
    private String name;
    private String grade;
    private String financialYear;
    private List<SalaryComponentResponse> components;
    private LocalDateTime createdAt;
}
