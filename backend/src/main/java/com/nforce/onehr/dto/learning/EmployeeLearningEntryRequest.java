package com.nforce.onehr.dto.learning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class EmployeeLearningEntryRequest {
    @NotBlank private String title;
    private String description;
    @NotNull private LocalDate entryDate;
}
