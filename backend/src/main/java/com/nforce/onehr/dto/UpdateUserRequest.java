package com.nforce.onehr.dto;

import lombok.Data;

import java.util.UUID;

// Super Admin scope — all fields editable including manager (triggers history) and role.
@Data
public class UpdateUserRequest {
    private String fullName;
    private String role;
    private UUID departmentId;
    private UUID designationId;
    private UUID locationId;
    private String employmentType;
    private String workMode;
    private UUID managerId;
}
