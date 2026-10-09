package com.nforce.onehr.dto.talent;

import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.dto.learning.EmployeeLearningEntryResponse;
import com.nforce.onehr.dto.skills.EmployeeSkillResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** One row of the org-wide Talent & Skills Employee Directory/Dashboard table — same
 * Skills/Certificates/Learning shape {@link com.nforce.onehr.dto.TeamGrowthSummary} already
 * carries for a manager's direct reports, plus the department/location/designation fields the
 * org-wide view's filters need that a manager's own team view has no use for. */
@Data @Builder
public class TalentEmployeeSummary {
    private UUID employeeUserId;
    private String employeeName;
    private String employeeCode;
    private String departmentName;
    private String locationName;
    private String designationName;
    private String managerName;
    private List<EmployeeSkillResponse> skills;
    private List<EmployeeCertificateResponse> certificates;
    private List<EmployeeLearningEntryResponse> learningEntries;
}
