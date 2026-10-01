package com.nforce.onehr.dto.learning;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class EmployeeLearningEntryResponse {
    private UUID id;
    private String title;
    private String description;
    private LocalDate entryDate;
    private Instant createdAt;
    private Instant updatedAt;
}
