package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class ProfileResponse {

    // Identity
    private UUID userId;
    private String email;
    private String fullName;
    private String role;
    private String photoDataUrl;

    // Self-service editable
    private String phone;
    private LocalDate dateOfBirth;
    private String gender;
    private String personalEmail;
    private String address;
    private String emergencyContactName;
    private String emergencyContactPhone;
    private String workMode;

    // Employment — read-only, HR-managed
    private String employeeCode;
    private String departmentName;
    private String designationName;
    private String locationName;
    private String employmentType;
    private LocalDate joiningDate;
    private String managerName;
    private String managerEmail;
    private boolean active;
    private boolean hasEmployeeRecord;
}
