package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data @Builder
public class ManagerDashboardDto {
    private int directReportCount;
    private List<DirectReport> directReports;

    @Data @Builder
    public static class DirectReport {
        private String userId;
        private String employeeCode;
        private String fullName;
        private String designationName;
        private String departmentName;
        private boolean active;
    }
}
