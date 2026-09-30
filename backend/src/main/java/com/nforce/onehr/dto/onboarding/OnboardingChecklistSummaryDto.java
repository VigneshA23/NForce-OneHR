package com.nforce.onehr.dto.onboarding;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class OnboardingChecklistSummaryDto {
    private UUID checklistId;
    private UUID employeeUserId;
    private String employeeName;
    private String employeeCode;
    private String departmentName;
    private String designationName;
    private LocalDate joiningDate;

    private boolean archived;
    private String status;          // ON_TRACK | ATTENTION | OVERDUE | COMPLETE
    private int totalItems;
    private int doneItems;
    private String nextDueLabel;
    private LocalDate nextDueDate;

    private LocalDate completedDate;
    private Long durationDays;
}
