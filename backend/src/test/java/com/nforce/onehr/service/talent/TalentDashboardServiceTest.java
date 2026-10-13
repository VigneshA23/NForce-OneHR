package com.nforce.onehr.service.talent;

import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.talent.NameCount;
import com.nforce.onehr.dto.talent.TalentDashboardSummary;
import com.nforce.onehr.entity.EmployeeCertificate;
import com.nforce.onehr.entity.EmployeeSkill;
import com.nforce.onehr.entity.SkillMaster;
import com.nforce.onehr.repository.CertificateCategoryRepository;
import com.nforce.onehr.repository.EmployeeCertificateRepository;
import com.nforce.onehr.repository.EmployeeLearningEntryRepository;
import com.nforce.onehr.repository.EmployeeSkillRepository;
import com.nforce.onehr.repository.SkillMasterRepository;
import com.nforce.onehr.service.EmployeeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TalentDashboardServiceTest {

    @Mock private EmployeeService employeeService;
    @Mock private EmployeeSkillRepository employeeSkillRepo;
    @Mock private EmployeeCertificateRepository employeeCertificateRepo;
    @Mock private EmployeeLearningEntryRepository employeeLearningEntryRepo;
    @Mock private SkillMasterRepository skillMasterRepo;
    @Mock private CertificateCategoryRepository certificateCategoryRepo;
    @InjectMocks private TalentDashboardService service;

    private static DirectoryEntryDto employee(UUID id, String dept) {
        return DirectoryEntryDto.builder().userId(id.toString()).departmentName(dept).build();
    }

    @Test
    void topSkills_groupsAMigratedRowByItsResolvedNameNotTheRawText() {
        UUID employeeId = UUID.randomUUID();
        UUID javaSkillId = UUID.randomUUID();
        when(employeeService.listDirectory()).thenReturn(List.of(employee(employeeId, "Engineering")));
        // Two rows that are really the same skill: one already resolved (skill_id set), one not.
        EmployeeSkill migrated = EmployeeSkill.builder().employeeUserId(employeeId).skillName("Core Java")
                .proficiencyLevel("Expert").skillId(javaSkillId).build();
        EmployeeSkill unmigrated = EmployeeSkill.builder().employeeUserId(employeeId).skillName("Java")
                .proficiencyLevel("Beginner").build();
        when(employeeSkillRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(migrated, unmigrated));
        when(employeeCertificateRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(employeeLearningEntryRepo.findByEmployeeUserIdInOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(skillMasterRepo.findAllByOrderByNameAsc()).thenReturn(List.of(
                SkillMaster.builder().id(javaSkillId).name("Java").build()));
        when(certificateCategoryRepo.findAllByOrderByNameAsc()).thenReturn(List.of());

        TalentDashboardSummary summary = service.summarize();

        // The migrated row resolves to "Java" (the master name), the unmigrated one is ALREADY
        // literally "Java" too, so both land in the same bucket — count 2, not two separate bars.
        assertEquals(1, summary.getTopSkills().size());
        assertEquals(new NameCount("Java", 2), summary.getTopSkills().get(0));
    }

    @Test
    void certificatesExpiringIn30Days_excludesCertificatesOutsideTheWindow() {
        UUID employeeId = UUID.randomUUID();
        when(employeeService.listDirectory()).thenReturn(List.of(employee(employeeId, "Engineering")));
        when(employeeSkillRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        EmployeeCertificate expiringSoon = EmployeeCertificate.builder().employeeUserId(employeeId)
                .name("AWS").expiryDate(LocalDate.now().plusDays(10)).build();
        EmployeeCertificate expiringLater = EmployeeCertificate.builder().employeeUserId(employeeId)
                .name("Azure").expiryDate(LocalDate.now().plusDays(80)).build();
        EmployeeCertificate alreadyExpired = EmployeeCertificate.builder().employeeUserId(employeeId)
                .name("Old").expiryDate(LocalDate.now().minusDays(5)).build();
        when(employeeCertificateRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(expiringSoon, expiringLater, alreadyExpired));
        when(employeeLearningEntryRepo.findByEmployeeUserIdInOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(skillMasterRepo.findAllByOrderByNameAsc()).thenReturn(List.of());
        when(certificateCategoryRepo.findAllByOrderByNameAsc()).thenReturn(List.of());

        TalentDashboardSummary summary = service.summarize();

        assertEquals(1, summary.getCertificatesExpiringIn30Days(), "only the one expiring in 10 days counts");
        assertEquals(1, summary.getCertificateExpiryBreakdown().getIn30Days());
        assertEquals(2, summary.getCertificateExpiryBreakdown().getIn90Days(), "30 and 80 days both fall within 90");
    }

    @Test
    void summarize_returnsEmptySummaryWithNoQueriesWhenOrgHasNoEmployees() {
        when(employeeService.listDirectory()).thenReturn(List.of());

        TalentDashboardSummary summary = service.summarize();

        assertEquals(0, summary.getTotalEmployees());
        assertTrue(summary.getTopSkills().isEmpty());
        org.mockito.Mockito.verifyNoInteractions(employeeSkillRepo, employeeCertificateRepo, employeeLearningEntryRepo);
    }
}
