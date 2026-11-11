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
    // Only populated for reporting-manager candidates: whether they hold leave:APPROVE:TEAM, i.e. could
    // actually decide their reports' requests. Lets the picker warn before routing requests to someone who can't.
    private Boolean canApproveRequests;
    // Which role list(s) this person belongs to — assigned roles minus the baseline EMPLOYEE, or
    // [Employee] when they have none (see RoleCategorization). Drives role filters/badges; it does
    // not affect which people the Employee Master lists (everyone).
    private java.util.List<com.nforce.onehr.util.RoleCategorization.Category> categories;
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
