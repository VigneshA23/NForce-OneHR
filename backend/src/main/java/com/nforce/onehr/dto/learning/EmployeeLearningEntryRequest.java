package com.nforce.onehr.dto.learning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class EmployeeLearningEntryRequest {

    @NotBlank
    private String title;

    private String description;

    @NotBlank
    @Pattern(regexp = "^(Course|Certification|Self Learning|Workshop|Training|Conference|Internal Training|Other)$",
            message = "Invalid learning type")
    private String learningType;

    @NotBlank
    @Pattern(regexp = "^(Planned|In Progress|Completed)$", message = "Invalid status")
    private String status;

    private String provider;

    private LocalDate startDate;

    // Required only when status is Completed — enforced in the service, not here, since it's
    // conditional on another field rather than a plain per-field constraint.
    private LocalDate completedDate;

    private List<String> skillsDeveloped;

    private String certificateName;
    private LocalDate certificateIssueDate;
    private LocalDate certificateExpiryDate;
    private String certificateUrl;

    private String notes;
}
