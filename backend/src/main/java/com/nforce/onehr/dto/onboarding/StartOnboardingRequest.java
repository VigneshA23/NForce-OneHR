package com.nforce.onehr.dto.onboarding;

import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Either set employeeUserId (pick from Employee Master) OR leave it null and
 * fill the inline new-hire fields — OnboardingService delegates to
 * EmployeeService.createEmployee for the latter so no employee-creation logic
 * is duplicated here.
 */
@Data
public class StartOnboardingRequest {

    private UUID employeeUserId;

    // Inline new-hire fields — only read when employeeUserId is null.
    private String fullName;
    private String email;
    private String employeeCode;
    private UUID departmentId;
    private UUID designationId;
    private UUID locationId;
    private String employmentType;
    private String workMode;
    private LocalDate joiningDate;
    private UUID managerId;
}
