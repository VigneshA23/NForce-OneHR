package com.nforce.onehr.dto.assignments;

import lombok.*;

import java.util.UUID;

/** One row of the Employee Assignments table (ONEHR-108). */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmployeeAssignmentRow {

    private UUID employeeUserId;
    private String employeeCode;
    private String fullName;
    private String departmentName;
    private String locationName;

    private UUID shiftId;
    private String shiftName;
    private UUID weeklyOffPolicyId;
    private String weeklyOffPolicyName;
    private UUID penalisationPolicyId;
    private String penalisationPolicyName;
}
