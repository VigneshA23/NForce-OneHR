package com.nforce.onehr.dto;

import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.dto.learning.EmployeeLearningEntryResponse;
import com.nforce.onehr.dto.skills.EmployeeSkillResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** A direct report's own Skills tab content (Skills & Expertise, Certificates, Learning &
 * Development), as the manager read-only view on My Team — see TeamGrowthService. */
@Data @Builder
public class TeamGrowthResponse {
    private UUID employeeUserId;
    private String employeeName;
    private List<EmployeeSkillResponse> skills;
    private List<EmployeeCertificateResponse> certificates;
    private List<EmployeeLearningEntryResponse> learningEntries;
}
