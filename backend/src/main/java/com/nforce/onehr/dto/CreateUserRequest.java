package com.nforce.onehr.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

// Super Admin scope — role field included, all 4 Phase 1 roles allowed.
@Data
public class CreateUserRequest {
    @NotBlank
    private String fullName;

    @NotBlank @Email
    private String email;

    // EMPLOYEE | MANAGER | HR_ADMIN | SUPER_ADMIN
    @NotBlank
    private String role;

    private String employeeCode;
    private UUID departmentId;
    private UUID designationId;
    private UUID locationId;
    private String employmentType = "FULL_TIME";
    private String workMode = "ONSITE";

    @NotNull
    private LocalDate joiningDate;

    private UUID managerId;
}
