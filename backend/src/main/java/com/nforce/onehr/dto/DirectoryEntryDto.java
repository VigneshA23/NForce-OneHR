package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class DirectoryEntryDto {
    private String userId;
    private String employeeCode;
    private String fullName;
    private String email;
    private String departmentName;
    private String designationName;
    private String locationName;
    private String workMode;
    private String employmentType;
    private boolean active;
    private String managerName;
    private String managerEmail;
}
