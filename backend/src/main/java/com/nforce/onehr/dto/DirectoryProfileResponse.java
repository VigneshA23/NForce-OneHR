package com.nforce.onehr.dto;

import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.dto.learning.EmployeeLearningEntryResponse;
import com.nforce.onehr.dto.skills.EmployeeSkillResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** The "View Profile" popup from the People Directory — About/Job detail (see ProfileResponse,
 * redacted per viewer role in ProfileService#getProfileForViewer) plus the same Skills &
 * Expertise / Certificates / Learning & Development an employee's own Skills tab shows. Unlike
 * TeamGrowthResponse (manager's direct reports only), this is org-wide: any authenticated
 * employee can view any other employee's profile this way, same exposure level the directory
 * listing itself already has. */
@Data @Builder
public class DirectoryProfileResponse {
    private ProfileResponse profile;
    private List<EmployeeSkillResponse> skills;
    private List<EmployeeCertificateResponse> certificates;
    private List<EmployeeLearningEntryResponse> learningEntries;
}
