package com.nforce.onehr.dto;

import lombok.Data;

import java.util.UUID;

// HR Admin scope — only dept/designation/location/employment_type.
// Manager and role changes are Super-Admin-only via /api/users.
@Data
public class UpdateEmployeeRequest {
    private UUID departmentId;
    private UUID designationId;
    private UUID locationId;
    private String employmentType;
    private String workMode;
    private String fullName;
}
