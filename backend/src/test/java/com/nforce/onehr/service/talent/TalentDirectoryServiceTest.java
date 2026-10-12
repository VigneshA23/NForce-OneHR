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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TalentDirectoryServiceTest {

    @Mock private EmployeeService employeeService;
    @Mock private EmployeeSkillService skillService;
    @Mock private EmployeeCertificateService certificateService;
    @Mock private EmployeeLearningEntryService learningEntryService;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @InjectMocks private TalentDirectoryService service;

    private static DirectoryEntryDto entry(UUID id, String name, String dept) {
        return DirectoryEntryDto.builder().userId(id.toString()).fullName(name)
                .employeeCode("NF-1").departmentName(dept).build();
    }

    @Test
    void listAll_isOrgWideNotScopedToAnyManager() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(employeeService.listDirectory()).thenReturn(List.of(entry(a, "Alice", "Engineering"), entry(b, "Bob", "Sales")));
        when(skillService.listForEmployees(any())).thenReturn(Map.of());
        when(certificateService.listForEmployees(any())).thenReturn(Map.of());
        when(learningEntryService.listForEmployees(any())).thenReturn(Map.of());

        List<TalentEmployeeSummary> result = service.listAll();

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(r -> r.getEmployeeName().equals("Alice") && r.getDepartmentName().equals("Engineering")));
        assertTrue(result.stream().anyMatch(r -> r.getEmployeeName().equals("Bob") && r.getDepartmentName().equals("Sales")));
    }

    @Test
    void listAll_returnsEmptyWithoutQueryingWhenNoEmployees() {
        when(employeeService.listDirectory()).thenReturn(List.of());

        List<TalentEmployeeSummary> result = service.listAll();

        assertTrue(result.isEmpty());
        verifyNoInteractions(skillService, certificateService, learningEntryService);
    }

    @Test
    void getEmployeeDetail_logsAuditAndReturnsFullData() {
        UUID employeeId = UUID.randomUUID();
        when(employeeService.listDirectory()).thenReturn(List.of(entry(employeeId, "Alice", "Engineering")));
        EmployeeSkillResponse skill = mock(EmployeeSkillResponse.class);
        EmployeeCertificateResponse cert = mock(EmployeeCertificateResponse.class);
        EmployeeLearningEntryResponse learning = mock(EmployeeLearningEntryResponse.class);
        when(skillService.listForEmployee(employeeId)).thenReturn(List.of(skill));
        when(certificateService.listForEmployee(employeeId)).thenReturn(List.of(cert));
        when(learningEntryService.listForEmployee(employeeId)).thenReturn(List.of(learning));
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findByEmail("hr@test.com")).thenReturn(Optional.of(User.builder().id(viewerId).build()));

        TeamGrowthResponse result = service.getEmployeeDetail(employeeId, "hr@test.com");

        assertEquals("Alice", result.getEmployeeName());
        assertEquals(1, result.getSkills().size());
        verify(auditService).log(viewerId, "TALENT_PROFILE_VIEWED", employeeId);
    }

    @Test
    void getEmployeeDetail_throwsForUnknownEmployee() {
        when(employeeService.listDirectory()).thenReturn(List.of());
        UUID unknownId = UUID.randomUUID();

        assertThrows(NoSuchElementException.class, () -> service.getEmployeeDetail(unknownId, "hr@test.com"));
    }
}
