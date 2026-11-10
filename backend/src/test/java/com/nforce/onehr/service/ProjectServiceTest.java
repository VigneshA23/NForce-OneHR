package com.nforce.onehr.service;

import com.nforce.onehr.dto.allocation.ClientRequest;
import com.nforce.onehr.dto.allocation.ProjectRequest;
import com.nforce.onehr.dto.allocation.ProjectResponse;
import com.nforce.onehr.entity.Client;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectStatus;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.ClientRepository;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ProjectRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * US-B01 (project reference data: unique code, status, no delete), US-B02 (client lookup tag) and
 * US-B03 (owner must be a Manager; Managers list only their own projects).
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ClientRepository clientRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private UserRepository userRepository;
    // The REAL permission matrix from the migrations, plus test-only custom roles.
    private final com.nforce.onehr.security.EffectivePermissionService effectivePermissionService =
            com.nforce.onehr.security.SeedMatrix.permissionService(java.util.Map.of(
                    "CUSTOM_PROJECT_LEAD", java.util.Map.of("projects:VIEW", "TEAM"),
                    "CUSTOM_PROJECT_ADMIN", java.util.Map.of("projects:VIEW", "ALL"),
                    "CUSTOM_ROLES_ONLY", java.util.Map.of("roles:VIEW", "ALL")));

    private ProjectService service;

    private final UUID departmentId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProjectService(projectRepository, clientRepository, departmentRepository,
                employeeRepository, userRepository, effectivePermissionService);
        lenient().when(projectRepository.save(any())).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
    }

    private static User user(UUID id, String roleCode) {
        return User.builder().id(id).email(roleCode.toLowerCase() + "@test.com")
                .roles(Set.of(Role.builder().code(roleCode).build())).build();
    }

    private Employee employeeWithRole(UUID id, String roleCode) {
        return Employee.builder().userId(id).fullName("Owner " + roleCode).user(user(id, roleCode)).build();
    }

    private ProjectRequest request(String code) {
        ProjectRequest req = new ProjectRequest();
        req.setCode(code);
        req.setName("Phoenix");
        req.setStartDate(LocalDate.of(2026, 1, 1));
        req.setEndDate(LocalDate.of(2026, 12, 31));
        return req;
    }

    @Test
    void create_savesAllFieldsAndDefaultsToActive() {
        ProjectRequest req = request("  PHX-01 ");
        req.setDepartmentId(departmentId);
        req.setClientId(clientId);
        req.setOwnerEmployeeId(managerId);
        when(departmentRepository.existsById(departmentId)).thenReturn(true);
        when(clientRepository.existsById(clientId)).thenReturn(true);
        when(employeeRepository.findById(managerId)).thenReturn(Optional.of(employeeWithRole(managerId, "MANAGER")));
        when(employeeRepository.findAllById(any())).thenReturn(List.of(employeeWithRole(managerId, "MANAGER")));
        when(departmentRepository.findAllById(any())).thenReturn(List.of(Department.builder().id(departmentId).name("Engineering").build()));
        when(clientRepository.findAllById(any())).thenReturn(List.of(Client.builder().id(clientId).name("Acme").build()));

        ProjectResponse res = service.create(req);

        assertEquals("PHX-01", res.getCode());
        assertEquals(ProjectStatus.ACTIVE, res.getStatus());
        assertEquals("Owner MANAGER", res.getOwnerName());
        assertEquals("Engineering", res.getDepartmentName());
        assertEquals("Acme", res.getClientName());
    }

    @Test
    void create_rejectsDuplicateCodeCaseInsensitively() {
        when(projectRepository.existsByCodeIgnoreCase("phx-01")).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.create(request("phx-01")));
        verify(projectRepository, never()).save(any());
    }

    @Test
    void update_rejectsCodeUsedByAnotherProject() {
        UUID id = UUID.randomUUID();
        when(projectRepository.findById(id)).thenReturn(Optional.of(Project.builder().id(id).code("A").build()));
        when(projectRepository.existsByCodeIgnoreCaseAndIdNot("B", id)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.update(id, request("B")));
        verify(projectRepository, never()).save(any());
    }

    @Test
    void update_toClosed_isAStatusChangeOnly() {
        UUID id = UUID.randomUUID();
        Project existing = Project.builder().id(id).code("PHX").name("Phoenix").status(ProjectStatus.ACTIVE).build();
        when(projectRepository.findById(id)).thenReturn(Optional.of(existing));
        ProjectRequest req = request("PHX");
        req.setStatus(ProjectStatus.CLOSED);

        ProjectResponse res = service.update(id, req);

        assertEquals(ProjectStatus.CLOSED, res.getStatus());
        verify(projectRepository).save(existing);
        verify(projectRepository, never()).delete(any());
        verify(projectRepository, never()).deleteById(any());
    }

    @Test
    void create_rejectsEndDateBeforeStartDate() {
        ProjectRequest req = request("PHX");
        req.setEndDate(LocalDate.of(2025, 1, 1));

        assertThrows(IllegalArgumentException.class, () -> service.create(req));
    }

    @Test
    void create_rejectsUnknownDepartment() {
        ProjectRequest req = request("PHX");
        req.setDepartmentId(departmentId);
        when(departmentRepository.existsById(departmentId)).thenReturn(false);

        assertThrows(NoSuchElementException.class, () -> service.create(req));
    }

    @Test
    void create_rejectsOwnerWhoIsNotAManager() {
        ProjectRequest req = request("PHX");
        req.setOwnerEmployeeId(managerId);
        when(employeeRepository.findById(managerId)).thenReturn(Optional.of(employeeWithRole(managerId, "EMPLOYEE")));

        assertThrows(IllegalArgumentException.class, () -> service.create(req));
        verify(projectRepository, never()).save(any());
    }

    @Test
    void create_acceptsAnOwnerWithProjectResponsibility_evenWithoutTheManagerRole() {
        ProjectRequest req = request("PHX");
        req.setOwnerEmployeeId(managerId);
        when(employeeRepository.findById(managerId)).thenReturn(Optional.of(employeeWithRole(managerId, "CUSTOM_PROJECT_LEAD")));
        when(employeeRepository.findAllById(any())).thenReturn(List.of(employeeWithRole(managerId, "CUSTOM_PROJECT_LEAD")));

        assertEquals("PHX", service.create(req).getCode());
    }

    @Test
    void create_rejectsAnOwnerWhoseRolesGrantNoProjectResponsibility() {
        ProjectRequest req = request("PHX");
        req.setOwnerEmployeeId(managerId);
        when(employeeRepository.findById(managerId)).thenReturn(Optional.of(employeeWithRole(managerId, "CUSTOM_ROLES_ONLY")));

        assertThrows(IllegalArgumentException.class, () -> service.create(req));
        verify(projectRepository, never()).save(any());
    }

    @Test
    void list_customRoleWithViewAll_seesEveryProject_likeHrAdmin() {
        when(userRepository.findByEmail("custom_project_admin@test.com"))
                .thenReturn(Optional.of(user(UUID.randomUUID(), "CUSTOM_PROJECT_ADMIN")));
        when(projectRepository.findAllByOrderByNameAsc()).thenReturn(List.of(
                Project.builder().id(UUID.randomUUID()).code("A").name("A").build()));

        assertEquals(1, service.list("custom_project_admin@test.com").size());
        verify(projectRepository, never()).findByOwnerEmployeeIdOrderByNameAsc(any());
    }

    @Test
    void list_hrAdminSeesEveryProject() {
        when(userRepository.findByEmail("hr@test.com")).thenReturn(Optional.of(user(UUID.randomUUID(), "HR_ADMIN")));
        when(projectRepository.findAllByOrderByNameAsc()).thenReturn(List.of(
                Project.builder().id(UUID.randomUUID()).code("A").name("A").build(),
                Project.builder().id(UUID.randomUUID()).code("B").name("B").build()));

        assertEquals(2, service.list("hr@test.com").size());
        verify(projectRepository, never()).findByOwnerEmployeeIdOrderByNameAsc(any());
    }

    @Test
    void list_managerSeesOnlyProjectsTheyOwn() {
        when(userRepository.findByEmail("manager@test.com")).thenReturn(Optional.of(user(managerId, "MANAGER")));
        when(projectRepository.findByOwnerEmployeeIdOrderByNameAsc(managerId)).thenReturn(List.of(
                Project.builder().id(UUID.randomUUID()).code("A").name("A").ownerEmployeeId(managerId).build()));

        List<ProjectResponse> res = service.list("manager@test.com");

        assertEquals(1, res.size());
        verify(projectRepository, never()).findAllByOrderByNameAsc();
    }

    @Test
    void createClient_rejectsDuplicateName() {
        when(clientRepository.existsByNameIgnoreCase("Acme")).thenReturn(true);
        ClientRequest req = new ClientRequest();
        req.setName(" Acme ");

        assertThrows(IllegalStateException.class, () -> service.createClient(req));
        verify(clientRepository, never()).save(any());
    }
}
