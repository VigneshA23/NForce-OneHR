package com.nforce.onehr.dto.learning;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class EmployeeLearningEntryResponse {
    private UUID id;
    private String title;
    private String description;
    private String learningType;
    private String status;
    private String provider;
    private LocalDate startDate;
    private LocalDate completedDate;
    private List<String> skillsDeveloped;
    private String certificateName;
    private LocalDate certificateIssueDate;
    private LocalDate certificateExpiryDate;
    private String certificateUrl;
    private String verificationStatus;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
