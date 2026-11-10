package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data @Builder
public class EmployeeResponse {
    private UUID userId;
    private String employeeCode;
    private String fullName;
    private String email;
    private String role;
    // Every role held, by display name (incl. custom roles) — only populated by User Management;
    // `role` above stays the single primary role existing clients read.
    private java.util.List<String> roleNames;
    private String businessUnitId;
    private String businessUnitName;
    private String departmentId;
    private String departmentName;
    private String designationId;
    private String designationName;
    private String locationId;
    private String locationName;
    private String shiftId;
    private String shiftName;
    private String employmentType;
    private String workMode;
    private LocalDate joiningDate;
    private boolean active;
    private ManagerRef currentManager;
    private String tempPassword;

    @Data @Builder
    public static class ManagerRef {
        private String userId;
        private String fullName;
        private String email;
    }
}
