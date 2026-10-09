package com.nforce.onehr.dto;

import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.dto.learning.EmployeeLearningEntryResponse;
import com.nforce.onehr.dto.skills.EmployeeSkillResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** One row of the Team Performance roster's inline Skills/Certificates/Learning preview — the
 * same data TeamGrowthResponse carries for one employee, for every direct report at once. */
@Data @Builder
public class TeamGrowthSummary {
    private UUID employeeUserId;
    private String employeeName;
    private String employeeCode;
    private List<EmployeeSkillResponse> skills;
    private List<EmployeeCertificateResponse> certificates;
    private List<EmployeeLearningEntryResponse> learningEntries;
}
