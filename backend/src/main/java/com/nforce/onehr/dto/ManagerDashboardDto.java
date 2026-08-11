package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data @Builder
public class ManagerDashboardDto {
    private int directReportCount;
    private List<DirectReport> directReports;
    private List<TeamJoiner> teamJoiners;

    @Data @Builder
    public static class DirectReport {
        private String userId;
        private String employeeCode;
        private String fullName;
        private String designationName;
        private String departmentName;
        private boolean active;
    }

    /** One EmployeeManagerHistory row (a team-join event) for the "Team Joiners per Month" chart. */
    @Data @Builder
    public static class TeamJoiner {
        private String userId;
        private String employeeCode;
        private String fullName;
        private String designationName;
        private String departmentName;
        private boolean active;
        private String joinedTeamOn; // ISO date (yyyy-MM-dd) — EmployeeManagerHistory.effectiveFrom
    }
}
