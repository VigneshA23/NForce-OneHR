package com.nforce.onehr.service.talent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.talent.TalentSearchPageResponse;
import com.nforce.onehr.dto.talent.TalentSearchRequest;
import com.nforce.onehr.entity.EmployeeCertificate;
import com.nforce.onehr.entity.EmployeeSkill;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import com.nforce.onehr.service.EmployeeCertificateService;
import com.nforce.onehr.service.EmployeeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TalentSearchServiceTest {

    @Mock private EmployeeService employeeService;
    @Mock private EmployeeSkillRepository employeeSkillRepo;
    @Mock private EmployeeCertificateRepository employeeCertificateRepo;
    @Mock private EmployeeCertificateService employeeCertificateService;
    @Mock private EmployeeLearningEntryRepository employeeLearningEntryRepo;
    @Mock private SkillMasterRepository skillMasterRepo;
    @Mock private ProficiencyLevelRepository proficiencyLevelRepo;
    @Mock private SavedTalentSearchRepository savedSearchRepo;
    @Mock private UserRepository userRepository;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private TalentSearchService service;

    private static DirectoryEntryDto employee(UUID id, String name, String dept) {
        return DirectoryEntryDto.builder().userId(id.toString()).fullName(name).employeeCode("NF-1").departmentName(dept).build();
    }

    private void stubCommonEmptyData() {
        lenient().when(employeeCertificateRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        lenient().when(employeeCertificateService.listForEmployees(any())).thenReturn(Map.of());
        lenient().when(employeeLearningEntryRepo.findByEmployeeUserIdInOrderByCreatedAtDesc(any())).thenReturn(List.of());
        lenient().when(proficiencyLevelRepo.findAll()).thenReturn(List.of());
    }

    @Test
    void search_allMode_requiresEveryRequestedSkill() {
        UUID javaId = UUID.randomUUID();
        UUID seleniumId = UUID.randomUUID();
        UUID bothSkills = UUID.randomUUID();
        UUID onlyJava = UUID.randomUUID();
        when(employeeService.listDirectory()).thenReturn(List.of(
                employee(bothSkills, "Has Both", "Eng"), employee(onlyJava, "Only Java", "Eng")));
        stubCommonEmptyData();
        when(employeeSkillRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(
                EmployeeSkill.builder().employeeUserId(bothSkills).skillId(javaId).skillName("Java").proficiencyLevel("Expert").build(),
                EmployeeSkill.builder().employeeUserId(bothSkills).skillId(seleniumId).skillName("Selenium").proficiencyLevel("Advanced").build(),
                EmployeeSkill.builder().employeeUserId(onlyJava).skillId(javaId).skillName("Java").proficiencyLevel("Expert").build()
        ));

        TalentSearchRequest req = new TalentSearchRequest();
        req.setSkillIds(List.of(javaId, seleniumId));
        req.setMatchMode("ALL");

        TalentSearchPageResponse result = service.search(req);

        assertEquals(1, result.getTotalElements());
        assertEquals("Has Both", result.getItems().get(0).getEmployeeName());
    }

    @Test
    void search_anyMode_matchesEitherSkill() {
        UUID javaId = UUID.randomUUID();
        UUID pythonId = UUID.randomUUID();
        UUID javaDev = UUID.randomUUID();
        UUID pythonDev = UUID.randomUUID();
        UUID neitherDev = UUID.randomUUID();
        when(employeeService.listDirectory()).thenReturn(List.of(
                employee(javaDev, "Java Dev", "Eng"), employee(pythonDev, "Python Dev", "Eng"), employee(neitherDev, "Neither", "Eng")));
        stubCommonEmptyData();
        when(employeeSkillRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(
                EmployeeSkill.builder().employeeUserId(javaDev).skillId(javaId).skillName("Java").proficiencyLevel("Expert").build(),
                EmployeeSkill.builder().employeeUserId(pythonDev).skillId(pythonId).skillName("Python").proficiencyLevel("Expert").build()
        ));

        TalentSearchRequest req = new TalentSearchRequest();
        req.setSkillIds(List.of(javaId, pythonId));
        req.setMatchMode("ANY");

        TalentSearchPageResponse result = service.search(req);

        assertEquals(2, result.getTotalElements());
        assertTrue(result.getItems().stream().noneMatch(i -> i.getEmployeeName().equals("Neither")));
    }

    @Test
    void search_certifiedFilter_excludesEmployeesWithoutAnyCertificate() {
        UUID certified = UUID.randomUUID();
        UUID uncertified = UUID.randomUUID();
        when(employeeService.listDirectory()).thenReturn(List.of(
                employee(certified, "Certified", "Eng"), employee(uncertified, "Uncertified", "Eng")));
        when(employeeSkillRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(employeeCertificateRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(
                EmployeeCertificate.builder().employeeUserId(certified).name("AWS").build()));
        when(employeeCertificateService.listForEmployees(any())).thenReturn(Map.of());
        when(employeeLearningEntryRepo.findByEmployeeUserIdInOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(proficiencyLevelRepo.findAll()).thenReturn(List.of());

        TalentSearchRequest req = new TalentSearchRequest();
        req.setCertified(true);

        TalentSearchPageResponse result = service.search(req);

        assertEquals(1, result.getTotalElements());
        assertEquals("Certified", result.getItems().get(0).getEmployeeName());
    }

    @Test
    void search_departmentFilter_isCaseInsensitiveSubstringMatch() {
        UUID id = UUID.randomUUID();
        when(employeeService.listDirectory()).thenReturn(List.of(employee(id, "Alice", "Quality Engineering")));
        stubCommonEmptyData();
        when(employeeSkillRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());

        TalentSearchRequest req = new TalentSearchRequest();
        req.setDepartment("quality");

        TalentSearchPageResponse result = service.search(req);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void search_resultsSortedByMatchScoreDescendingByDefault() {
        UUID javaId = UUID.randomUUID();
        UUID oneMatch = UUID.randomUUID();
        UUID twoMatches = UUID.randomUUID();
        when(employeeService.listDirectory()).thenReturn(List.of(
                employee(oneMatch, "One Match", "Eng"), employee(twoMatches, "Two Matches", "Eng")));
        stubCommonEmptyData();
        when(employeeSkillRepo.findByEmployeeUserIdInOrderByCreatedAtAsc(any())).thenReturn(List.of(
                EmployeeSkill.builder().employeeUserId(oneMatch).skillId(javaId).skillName("Java").proficiencyLevel("Beginner").build(),
                EmployeeSkill.builder().employeeUserId(twoMatches).skillId(javaId).skillName("Java").proficiencyLevel("Beginner").build(),
                EmployeeSkill.builder().employeeUserId(twoMatches).skillId(UUID.randomUUID()).skillName("Python").proficiencyLevel("Beginner").build()
        ));

        TalentSearchRequest req = new TalentSearchRequest();
        req.setSkillIds(List.of(javaId));
        req.setMatchMode("ANY");

        TalentSearchPageResponse result = service.search(req);

        // Only Java was requested, so "Two Matches"' extra Python skill contributes nothing to
        // its score — both employees end up with exactly 1 matching skill, tied, broken by name.
        assertEquals("One Match", result.getItems().get(0).getEmployeeName());
    }

    @Test
    void deleteSavedSearch_rejectsDeletingSomeoneElsesSearch() {
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        com.nforce.onehr.entity.SavedTalentSearch saved = com.nforce.onehr.entity.SavedTalentSearch.builder()
                .id(UUID.randomUUID()).ownerUserId(ownerId).name("x").queryJson("{}").build();
        when(userRepository.findByEmail("other@test.com")).thenReturn(Optional.of(User.builder().id(otherUserId).build()));
        when(savedSearchRepo.findById(saved.getId())).thenReturn(Optional.of(saved));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.deleteSavedSearch(saved.getId(), "other@test.com"));
    }
}
