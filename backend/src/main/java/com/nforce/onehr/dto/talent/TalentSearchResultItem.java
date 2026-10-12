package com.nforce.onehr.dto.talent;

import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

@Value
@Builder
public class TalentSearchResultItem {
    UUID employeeUserId;
    String employeeName;
    String employeeCode;
    String departmentName;
    String managerName;
    List<MatchedSkill> matchingSkills;
    List<EmployeeCertificateResponse> certificates;
    // The title of the employee's most recently started Planned/In Progress learning entry, or
    // null if they have none currently active — "current learning track" per the spec's results
    // column, not their whole learning history (that's the Directory drill-down's job).
    String currentLearningTrack;
    int matchScore;
}
