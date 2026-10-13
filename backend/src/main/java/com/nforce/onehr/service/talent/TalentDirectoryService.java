package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.TeamGrowthResponse;
import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.dto.learning.EmployeeLearningEntryResponse;
import com.nforce.onehr.dto.skills.EmployeeSkillResponse;
import com.nforce.onehr.dto.talent.TalentEmployeeSummary;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.service.AuditService;
import com.nforce.onehr.service.EmployeeCertificateService;
import com.nforce.onehr.service.EmployeeLearningEntryService;
import com.nforce.onehr.service.EmployeeService;
import com.nforce.onehr.service.EmployeeSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Org-wide Talent & Skills Employee Directory + the Dashboard table's backing data
 * (org.talent.view — HR_ADMIN/SUPER_ADMIN only, enforced at the controller). The exact same
 * bulk-fetch shape as {@link com.nforce.onehr.service.TeamGrowthService#listTeamGrowthSummaries},
 * just without that class's direct-report filter — every active employee, not just one manager's
 * reports. Reuses {@link EmployeeService#listDirectory()} (already org-wide, already used by
 * People Directory and EmployeeSearchProvider) as the base employee list, and the exact same
 * {@link EmployeeSkillService#listForEmployees}/certificate/learning bulk methods
 * TeamGrowthService already relies on — three bulk queries total for the whole org, not
 * three-per-employee.
 */
@Service
@RequiredArgsConstructor
public class TalentDirectoryService {

    private final EmployeeService employeeService;
    private final EmployeeSkillService skillService;
    private final EmployeeCertificateService certificateService;
    private final EmployeeLearningEntryService learningEntryService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<TalentEmployeeSummary> listAll() {
        List<DirectoryEntryDto> all = employeeService.listDirectory();
        List<UUID> ids = all.stream().map(e -> UUID.fromString(e.getUserId())).collect(Collectors.toList());
        if (ids.isEmpty()) return List.of();

        Map<UUID, List<EmployeeSkillResponse>> skillsByEmployee = skillService.listForEmployees(ids);
        Map<UUID, List<EmployeeCertificateResponse>> certsByEmployee = certificateService.listForEmployees(ids);
        Map<UUID, List<EmployeeLearningEntryResponse>> learningByEmployee = learningEntryService.listForEmployees(ids);

        return all.stream()
                .map(e -> {
                    UUID id = UUID.fromString(e.getUserId());
                    return TalentEmployeeSummary.builder()
                            .employeeUserId(id)
                            .employeeName(e.getFullName())
                            .employeeCode(e.getEmployeeCode())
                            .departmentName(e.getDepartmentName())
                            .locationName(e.getLocationName())
                            .designationName(e.getDesignationName())
                            .managerName(e.getManagerName())
                            .skills(skillsByEmployee.getOrDefault(id, List.of()))
                            .certificates(certsByEmployee.getOrDefault(id, List.of()))
                            .learningEntries(learningByEmployee.getOrDefault(id, List.of()))
                            .build();
                })
                .collect(Collectors.toList());
    }

    /** Logs TALENT_PROFILE_VIEWED per the spec's "log every HR/Super Admin view of an individual
     * employee profile" requirement — {@link #listAll()} (the table/dashboard) does not log per
     * row, only this single-employee drill-down does. */
    @Transactional
    public TeamGrowthResponse getEmployeeDetail(UUID employeeUserId, String actorEmail) {
        DirectoryEntryDto entry = employeeService.listDirectory().stream()
                .filter(e -> e.getUserId().equals(employeeUserId.toString()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + employeeUserId));

        TeamGrowthResponse response = TeamGrowthResponse.builder()
                .employeeUserId(employeeUserId)
                .employeeName(entry.getFullName())
                .skills(skillService.listForEmployee(employeeUserId))
                .certificates(certificateService.listForEmployee(employeeUserId))
                .learningEntries(learningEntryService.listForEmployee(employeeUserId))
                .build();

        User actor = userRepository.findByEmail(actorEmail).orElse(null);
        if (actor != null) {
            auditService.log(actor.getId(), "TALENT_PROFILE_VIEWED", employeeUserId);
        }
        return response;
    }
}
