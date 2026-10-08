package com.nforce.onehr.service;

import com.nforce.onehr.dto.TeamGrowthResponse;
import com.nforce.onehr.dto.TeamGrowthSummary;
import com.nforce.onehr.dto.certificates.EmployeeCertificateResponse;
import com.nforce.onehr.dto.learning.EmployeeLearningEntryResponse;
import com.nforce.onehr.dto.skills.EmployeeSkillResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manager's read-only window into a direct report's own Skills tab (Skills & Expertise,
 * Certificates, Learning & Development) — surfaced from My Team, not a new editable surface.
 * Permission is the same DirectReportScopeService guarantee every other "see one of my reports'
 * data" feature in this codebase relies on: current direct reports only, whatever role the caller
 * holds, with HR_ADMIN/SUPER_ADMIN additionally allowed to widen.
 */
@Service
@RequiredArgsConstructor
public class TeamGrowthService {

    private static final Set<String> OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final DirectReportScopeService directReportScopeService;
    private final EmployeeSkillService skillService;
    private final EmployeeCertificateService certificateService;
    private final EmployeeLearningEntryService learningEntryService;

    @Transactional(readOnly = true)
    public TeamGrowthResponse getEmployeeGrowth(String actorEmail, UUID employeeUserId) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        boolean isOverride = actor.getRoles().stream().anyMatch(r -> OVERRIDE_ROLES.contains(r.getCode()));
        if (!isOverride && !directReportScopeService.isDirectReport(actorEmail, employeeUserId)) {
            throw new AccessDeniedException("This person is not your direct report");
        }
        String employeeName = employeeRepository.findById(employeeUserId)
                .map(Employee::getFullName)
                .orElseGet(() -> userRepository.findById(employeeUserId).map(User::getEmail).orElse("Unknown"));
        return TeamGrowthResponse.builder()
                .employeeUserId(employeeUserId)
                .employeeName(employeeName)
                .skills(skillService.listForEmployee(employeeUserId))
                .certificates(certificateService.listForEmployee(employeeUserId))
                .learningEntries(learningEntryService.listForEmployee(employeeUserId))
                .build();
    }

    // Powers the Team Performance roster's inline Skills/Certificates/Learning preview — one
    // row per direct report, built from 3 bulk queries total rather than 3-per-employee.
    @Transactional(readOnly = true)
    public List<TeamGrowthSummary> listTeamGrowthSummaries(String actorEmail) {
        List<Employee> reports = directReportScopeService.directReports(actorEmail);
        if (reports.isEmpty()) return List.of();
        List<UUID> ids = reports.stream().map(Employee::getUserId).collect(Collectors.toList());

        Map<UUID, List<EmployeeSkillResponse>> skillsByEmployee = skillService.listForEmployees(ids);
        Map<UUID, List<EmployeeCertificateResponse>> certsByEmployee = certificateService.listForEmployees(ids);
        Map<UUID, List<EmployeeLearningEntryResponse>> learningByEmployee = learningEntryService.listForEmployees(ids);

        return reports.stream()
                .map(e -> TeamGrowthSummary.builder()
                        .employeeUserId(e.getUserId())
                        .employeeName(e.getFullName())
                        .employeeCode(e.getEmployeeCode())
                        .skills(skillsByEmployee.getOrDefault(e.getUserId(), List.of()))
                        .certificates(certsByEmployee.getOrDefault(e.getUserId(), List.of()))
                        .learningEntries(learningByEmployee.getOrDefault(e.getUserId(), List.of()))
                        .build())
                .collect(Collectors.toList());
    }
}
