package com.nforce.onehr.dto.onboarding;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data @Builder
public class OnboardingChecklistDetailDto {
    private UUID checklistId;
    private UUID employeeUserId;
    private String employeeName;
    private String employeeCode;
    private String departmentName;
    private String designationName;
    private String locationName;
    private String managerName;
    private LocalDate joiningDate;

    private boolean archived;
    private String status;
    private Instant completedAt;
    private int totalItems;
    private int doneItems;

    private List<OnboardingItemDto> preBoarding;
    private List<OnboardingItemDto> setup;
    private OnboardingItemDto documentsItem;
    private List<DocumentsBreakdownDto> documentsBreakdown;
    private List<TimelineEntryDto> timeline;
}
