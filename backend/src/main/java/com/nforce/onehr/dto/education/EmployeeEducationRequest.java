package com.nforce.onehr.dto.education;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class EmployeeEducationRequest {
    @NotBlank private String institutionName;
    @NotBlank private String degree;
    private String fieldOfStudy;
    private LocalDate startDate;
    private LocalDate endDate;
}
