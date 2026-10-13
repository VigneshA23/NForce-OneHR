package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.talent.CertificateExpiryBreakdown;
import com.nforce.onehr.dto.talent.NameCount;
import com.nforce.onehr.dto.talent.TalentDashboardSummary;
import com.nforce.onehr.entity.EmployeeCertificate;
import com.nforce.onehr.entity.EmployeeLearningEntry;
import com.nforce.onehr.entity.EmployeeSkill;
import com.nforce.onehr.entity.SkillMaster;
import com.nforce.onehr.repository.CertificateCategoryRepository;
import com.nforce.onehr.repository.EmployeeCertificateRepository;
import com.nforce.onehr.repository.EmployeeLearningEntryRepository;
import com.nforce.onehr.repository.EmployeeSkillRepository;
import com.nforce.onehr.repository.SkillMasterRepository;
import com.nforce.onehr.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Org-wide Talent & Skills Dashboard's KPI cards + charts (org.talent.view). Computed from the
 * same three bulk queries {@link TalentDirectoryService} already runs for the Directory table —
 * this reads the RAW entities directly (not the Response DTOs) specifically so it can group by
 * the resolved skill_master name wherever a row has been migrated (skill_id set), falling back to
 * the free-text skillName otherwise — "Java"/"java"/"Core Java" must count as one bar in Top
 * Skills regardless of migration state, which the self-service Response DTOs have no reason to
 * care about and so don't expose.
 *
 * <p>See {@link TalentDashboardSummary}'s own javadoc for what this deliberately does NOT compute
 * yet (skill gap view, learning trend over time, "Overdue" status) and why.
 */
@Service
@RequiredArgsConstructor
public class TalentDashboardService {

    private static final int TOP_SKILLS_LIMIT = 15;
    private static final int TOP_SKILLS_PER_DEPARTMENT_LIMIT = 10;

    private final EmployeeService employeeService;
    private final EmployeeSkillRepository employeeSkillRepo;
    private final EmployeeCertificateRepository employeeCertificateRepo;
    private final EmployeeLearningEntryRepository employeeLearningEntryRepo;
    private final SkillMasterRepository skillMasterRepo;
    private final CertificateCategoryRepository certificateCategoryRepo;

    @Transactional(readOnly = true)
    public TalentDashboardSummary summarize() {
        List<DirectoryEntryDto> employees = employeeService.listDirectory();
        List<UUID> ids = employees.stream().map(e -> UUID.fromString(e.getUserId())).collect(Collectors.toList());
        Map<UUID, String> departmentByEmployee = employees.stream()
                .collect(Collectors.toMap(e -> UUID.fromString(e.getUserId()),
                        e -> e.getDepartmentName() == null ? "Unassigned" : e.getDepartmentName()));

        if (ids.isEmpty()) {
            return TalentDashboardSummary.builder()
                    .topSkills(List.of()).proficiencyDistribution(List.of()).skillsByDepartment(Map.of())
                    .certificatesByCategory(List.of())
                    .certificateExpiryBreakdown(new CertificateExpiryBreakdown(0, 0, 0))
                    .learningStatusCounts(List.of()).build();
        }

        List<EmployeeSkill> allSkills = employeeSkillRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(ids);
        List<EmployeeCertificate> allCertificates = employeeCertificateRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(ids);
        List<EmployeeLearningEntry> allLearning = employeeLearningEntryRepo.findByEmployeeUserIdInOrderByCreatedAtDesc(ids);

        Map<UUID, String> skillNameById = skillMasterRepo.findAllByOrderByNameAsc().stream()
                .collect(Collectors.toMap(SkillMaster::getId, SkillMaster::getName));
        Map<UUID, String> categoryNameById = certificateCategoryRepo.findAllByOrderByNameAsc().stream()
                .collect(Collectors.toMap(com.nforce.onehr.entity.CertificateCategory::getId,
                        com.nforce.onehr.entity.CertificateCategory::getName));

        return TalentDashboardSummary.builder()
                .totalEmployees(ids.size())
                .withSkills(countDistinctEmployees(allSkills, EmployeeSkill::getEmployeeUserId))
                .withCertificates(countDistinctEmployees(allCertificates, EmployeeCertificate::getEmployeeUserId))
                .withActiveLearningTrack(allLearning.stream()
                        .filter(l -> "In Progress".equals(l.getStatus()) || "Planned".equals(l.getStatus()))
                        .map(EmployeeLearningEntry::getEmployeeUserId).distinct().count())
                .certificatesExpiringIn30Days(countExpiringWithin(allCertificates, 30))
                .topSkills(topSkills(allSkills, skillNameById, TOP_SKILLS_LIMIT))
                .proficiencyDistribution(countBy(allSkills, EmployeeSkill::getProficiencyLevel))
                .skillsByDepartment(skillsByDepartment(allSkills, skillNameById, departmentByEmployee))
                .certificatesByCategory(countBy(allCertificates,
                        c -> c.getCategoryId() == null ? "Uncategorized" : categoryNameById.getOrDefault(c.getCategoryId(), "Uncategorized")))
                .certificateExpiryBreakdown(new CertificateExpiryBreakdown(
                        countExpiringWithin(allCertificates, 30),
                        countExpiringWithin(allCertificates, 60),
                        countExpiringWithin(allCertificates, 90)))
                .learningStatusCounts(countBy(allLearning,
                        l -> "Planned".equals(l.getStatus()) ? "Not Started" : l.getStatus()))
                .build();
    }

    // ── Helpers ──

    private <T> long countDistinctEmployees(List<T> rows, java.util.function.Function<T, UUID> idFn) {
        return rows.stream().map(idFn).distinct().count();
    }

    private long countExpiringWithin(List<EmployeeCertificate> certs, int days) {
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.plusDays(days);
        return certs.stream()
                .filter(c -> c.getExpiryDate() != null && !c.getExpiryDate().isBefore(today) && !c.getExpiryDate().isAfter(cutoff))
                .count();
    }

    private List<NameCount> topSkills(List<EmployeeSkill> skills, Map<UUID, String> skillNameById, int limit) {
        Map<String, Long> counts = skills.stream()
                .collect(Collectors.groupingBy(s -> resolvedSkillName(s, skillNameById), Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new NameCount(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private Map<String, List<NameCount>> skillsByDepartment(List<EmployeeSkill> skills, Map<UUID, String> skillNameById,
                                                              Map<UUID, String> departmentByEmployee) {
        Map<String, List<EmployeeSkill>> byDept = skills.stream()
                .collect(Collectors.groupingBy(s -> departmentByEmployee.getOrDefault(s.getEmployeeUserId(), "Unassigned")));
        Map<String, List<NameCount>> result = new LinkedHashMap<>();
        byDept.forEach((dept, deptSkills) -> result.put(dept, topSkills(deptSkills, skillNameById, TOP_SKILLS_PER_DEPARTMENT_LIMIT)));
        return result;
    }

    private String resolvedSkillName(EmployeeSkill skill, Map<UUID, String> skillNameById) {
        if (skill.getSkillId() != null) {
            String name = skillNameById.get(skill.getSkillId());
            if (name != null) return name;
        }
        return skill.getSkillName();
    }

    private <T> List<NameCount> countBy(List<T> rows, java.util.function.Function<T, String> keyFn) {
        Map<String, Long> counts = rows.stream().collect(Collectors.groupingBy(keyFn, Collectors.counting()));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new NameCount(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }
}
