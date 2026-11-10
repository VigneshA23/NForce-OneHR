package com.nforce.onehr.dto.payroll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateSalaryStructureRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 50)
    private String grade;

    // E.g. "2026-27" — optional, free-text.
    @Size(max = 10)
    private String financialYear;

    // At least one component required — see SalaryStructureService#create (US-C01: "At least
    // one structure must exist before an employee can be enrolled in payroll" presumes every
    // structure actually has something to enroll an employee against).
    @NotEmpty
    @Valid
    private List<SalaryComponentRequest> components;
}
