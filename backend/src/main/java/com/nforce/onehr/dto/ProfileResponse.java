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

    // Self-service editable — ESS "My Profile" redesign
    private String firstName;
    private String middleName;
    private String lastName;
    private String preferredName;
    private String bio;
    private String maritalStatus;
    private String emergencyContactRelationship;
    private String permanentAddress;      // "address" above stays Current Address
    private String passportNumber;
    private LocalDate passportExpiry;
    private String bankAccountNumber;     // masked, e.g. "•••• •••• 5591"
    private String bankName;
    private String bankIfsc;
    private String nationalId;            // masked

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

    // Job tab — read-only, HR-managed
    private String jobCode;
    private LocalDate probationEndDate;
    private LocalDate confirmationDate;
    private String businessUnitName;
    private String shiftName;
    private String weeklyOffPolicyName;
    private String attendancePenalizationPolicyName;

    // Attendance indicator — "IN" | "OUT"
    private String attendanceStatus;
}
