package com.nforce.onehr.service;

import com.nforce.onehr.dto.allocation.ClientRequest;
import com.nforce.onehr.dto.allocation.ClientResponse;
import com.nforce.onehr.dto.allocation.ProjectRequest;
import com.nforce.onehr.dto.allocation.ProjectResponse;
import com.nforce.onehr.entity.Client;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectStatus;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.ClientRepository;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ProjectRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * US-B01 project reference data, US-B02 client lookup, US-B03 project owner.
 *
 * <p>There is deliberately no delete: closing a project is a {@link ProjectStatus#CLOSED} status
 * change via {@link #update}, so its historical allocations keep a valid FK. Clients are a plain
 * name lookup — no billing data of any kind (non-billable scope for this phase).
 *
 * <p>{@link #list} is the one read both HR and Managers use: HR_ADMIN/SUPER_ADMIN see every
 * project, anyone else only the projects they own (the ones they may allocate against, see
 * {@link ProjectAllocationService}).
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    // Seeded (V241/V248): VIEW:ALL -> HR_ADMIN + SUPER_ADMIN (every project); VIEW:TEAM -> additionally
    // MANAGER (own projects). A custom role granted either behaves the same — no role code involved.
    private static final String VIEW_ALL = "projects:VIEW:ALL";
    private static final String OWNER_AUTHORITY = "projects:VIEW:TEAM";

    private final ProjectRepository projectRepository;
    private final ClientRepository clientRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final EffectivePermissionService effectivePermissionService;

    @Transactional(readOnly = true)
    public List<ProjectResponse> list(String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        List<Project> projects = effectivePermissionService.hasAuthority(actor, VIEW_ALL)
                ? projectRepository.findAllByOrderByNameAsc()
                : projectRepository.findByOwnerEmployeeIdOrderByNameAsc(actor.getId());
        return toResponses(projects);
    }

    @Transactional
    public ProjectResponse create(ProjectRequest req) {
        String code = req.getCode().trim();
        String name = req.getName().trim();
        if (projectRepository.existsByCodeIgnoreCase(code)) {
            throw new IllegalStateException("Project code \"" + code + "\" is already in use");
        }
        if (projectRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalStateException("Project name \"" + name + "\" is already in use");
        }
        Project project = new Project();
        apply(project, req, code, name);
        if (project.getStatus() == null) project.setStatus(ProjectStatus.ACTIVE);
        return toResponses(List.of(projectRepository.save(project))).get(0);
    }

    /** Edits every field, including status — CLOSED/ON_HOLD stop new allocations but never touch existing ones. */
    @Transactional
    public ProjectResponse update(UUID id, ProjectRequest req) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Project not found"));
        String code = req.getCode().trim();
        String name = req.getName().trim();
        if (projectRepository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw new IllegalStateException("Project code \"" + code + "\" is already in use");
        }
        if (projectRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new IllegalStateException("Project name \"" + name + "\" is already in use");
        }
        apply(project, req, code, name);
        return toResponses(List.of(projectRepository.save(project))).get(0);
    }

    @Transactional(readOnly = true)
    public List<ClientResponse> listClients() {
        return clientRepository.findAllByOrderByNameAsc().stream()
                .map(c -> ClientResponse.builder().id(c.getId()).name(c.getName()).build())
                .toList();
    }

    @Transactional
    public ClientResponse createClient(ClientRequest req) {
        String name = req.getName().trim();
        if (clientRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalStateException("Client \"" + name + "\" already exists");
        }
        Client saved = clientRepository.save(Client.builder().name(name).build());
        return ClientResponse.builder().id(saved.getId()).name(saved.getName()).build();
    }

    private void apply(Project project, ProjectRequest req, String code, String name) {
        if (req.getEndDate() != null && req.getEndDate().isBefore(req.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        if (req.getDepartmentId() != null && !departmentRepository.existsById(req.getDepartmentId())) {
            throw new NoSuchElementException("Department not found");
        }
        if (req.getClientId() != null && !clientRepository.existsById(req.getClientId())) {
            throw new NoSuchElementException("Client not found");
        }
        if (req.getOwnerEmployeeId() != null) {
            requireEligibleOwner(req.getOwnerEmployeeId());
        }
        project.setCode(code);
        project.setName(name);
        project.setStartDate(req.getStartDate());
        project.setEndDate(req.getEndDate());
        project.setOwnerEmployeeId(req.getOwnerEmployeeId());
        project.setDepartmentId(req.getDepartmentId());
        project.setClientId(req.getClientId());
        if (req.getStatus() != null) project.setStatus(req.getStatus());
    }

    /** US-B03: the owner is the person who allocates on the project, so it must be someone with project responsibility. */
    private void requireEligibleOwner(UUID ownerEmployeeId) {
        Employee owner = employeeRepository.findById(ownerEmployeeId)
                .orElseThrow(() -> new NoSuchElementException("Project owner not found"));
        User user = owner.getUser();
        if (user == null || user.getDeletedAt() != null || !effectivePermissionService.hasAuthority(user, OWNER_AUTHORITY)) {
            throw new IllegalArgumentException("Project owner must be an active Manager");
        }
    }

    private List<ProjectResponse> toResponses(List<Project> projects) {
        Map<UUID, String> owners = employeeRepository.findAllById(ids(projects, Project::getOwnerEmployeeId)).stream()
                .collect(Collectors.toMap(Employee::getUserId, Employee::getFullName));
        Map<UUID, String> departments = departmentRepository.findAllById(ids(projects, Project::getDepartmentId)).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        Map<UUID, String> clients = clientRepository.findAllById(ids(projects, Project::getClientId)).stream()
                .collect(Collectors.toMap(Client::getId, Client::getName));
        return projects.stream().map(p -> ProjectResponse.builder()
                .id(p.getId())
                .code(p.getCode())
                .name(p.getName())
                .startDate(p.getStartDate())
                .endDate(p.getEndDate())
                .ownerEmployeeId(p.getOwnerEmployeeId())
                .ownerName(owners.get(p.getOwnerEmployeeId()))
                .departmentId(p.getDepartmentId())
                .departmentName(departments.get(p.getDepartmentId()))
                .clientId(p.getClientId())
                .clientName(clients.get(p.getClientId()))
                .status(p.getStatus())
                .build()).toList();
    }

    private static Collection<UUID> ids(List<Project> projects, Function<Project, UUID> getter) {
        return projects.stream().map(getter).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}
