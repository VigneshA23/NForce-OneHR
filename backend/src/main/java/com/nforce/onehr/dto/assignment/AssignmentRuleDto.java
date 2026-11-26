package com.nforce.onehr.dto.assignment;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssignmentRuleDto {
    private UUID id;
    private String domain;
    private UUID targetId;
    private String targetName;
    private String name;
    private int priority;
    private UUID departmentId;
    private String departmentName;
    private UUID locationId;
    private String locationName;
    private UUID shiftId;
    private String shiftName;
    private String employmentType;
    private String grade;
    private boolean active;
    /** Human summary of the criteria, e.g. "Department = Engineering AND Location = Bangalore". */
    private String criteriaText;
    /** Employees currently assigned by this rule. */
    private long assignedCount;
    private LocalDateTime updatedAt;
}
