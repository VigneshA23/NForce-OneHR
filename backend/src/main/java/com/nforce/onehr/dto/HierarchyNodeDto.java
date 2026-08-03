package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class HierarchyNodeDto {
    private String userId;
    private String fullName;
    private String designationName;
    private String departmentName;
    private String managerId;
    private boolean active;
}
