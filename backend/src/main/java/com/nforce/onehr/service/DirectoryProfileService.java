package com.nforce.onehr.service;

import com.nforce.onehr.dto.DirectoryProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Backs the People Directory's "View Profile" action — bundles the redacted-as-needed
 * ProfileResponse (ProfileService#getProfileForViewer) with the same Skills/Certificates/
 * Learning lists an employee's own Skills tab shows (EmployeeSkillService et al. #listForEmployee,
 * which do no permission check of their own by design — this is the org-wide-safe caller for
 * them, parallel to TeamGrowthService's direct-report-scoped one for My Team).
 */
@Service
@RequiredArgsConstructor
public class DirectoryProfileService {

    private final ProfileService profileService;
    private final EmployeeSkillService skillService;
    private final EmployeeCertificateService certificateService;
    private final EmployeeLearningEntryService learningEntryService;

    @Transactional(readOnly = true)
    public DirectoryProfileResponse getDirectoryProfile(String viewerEmail, UUID targetUserId) {
        return DirectoryProfileResponse.builder()
                .profile(profileService.getProfileForViewer(viewerEmail, targetUserId))
                .hasSensitiveAccess(profileService.hasSensitiveAccess(viewerEmail))
                .skills(skillService.listForEmployee(targetUserId))
                .certificates(certificateService.listForEmployee(targetUserId))
                .learningEntries(learningEntryService.listForEmployee(targetUserId))
                .build();
    }
}
