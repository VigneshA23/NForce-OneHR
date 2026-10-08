package com.nforce.onehr.service;

import com.nforce.onehr.dto.allocation.BulkAllocationResponse;
import com.nforce.onehr.dto.allocation.BulkAllocationResponse.AllocationResult;
import com.nforce.onehr.dto.allocation.BulkProjectAllocationRequest;
import com.nforce.onehr.dto.allocation.EmployeeCapacityResponse;
import com.nforce.onehr.dto.allocation.ProjectAllocationRequest;
import com.nforce.onehr.dto.allocation.ProjectAllocationResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectAllocation;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ProjectAllocationRepository;
import com.nforce.onehr.repository.ProjectRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Single source of truth for project-allocation capacity validation (US-B04 create / US-B06
 * capacity check): {@link #validateAndBuild} is the one place that decides whether an allocation
 * is legal. {@link #allocate} (single) and {@link #bulkAllocate} (US-B10) both call it instead of
 * re-implementing the rule — do not duplicate this logic in a new allocation path.
 *
 * <p>{@link #capacityView} (US-B05, filterable by {@link #capacityView} since US-B13) is the team
 * capacity read path. Its visibility is deliberately the same hierarchy rule {@link LeaveService}
 * uses for approvals — {@link DirectReportScopeService}'s direct-report set for a plain Manager,
 * unrestricted only for HR_ADMIN/SUPER_ADMIN — so a Manager's department/project filters can only
 * narrow that set further, never substitute for it.
 */
@Service
@RequiredArgsConstructor
public class ProjectAllocationService {

    private static final Set<String> VISIBILITY_OVERRIDE_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    private final ProjectAllocationRepository allocationRepository;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final DirectReportScopeService directReportScopeService;

    @Transactional
    public ProjectAllocationResponse allocate(ProjectAllocationRequest req) {
        Employee employee = findEmployee(req.getEmployeeId());
        Project project = findProject(req.getProjectId());
        ProjectAllocation allocation = allocationRepository.save(validateAndBuild(
                employee.getUserId(), project, req.getCapacityPercent(), req.getStartDate(), req.getEndDate()));
        return toResponse(allocation, employee, project);
    }

    /** US-B10: project/capacity/dates are shared across the request; each employee is still validated on its own. */
    @Transactional
    public BulkAllocationResponse bulkAllocate(BulkProjectAllocationRequest req) {
        Project project = findProject(req.getProjectId());
        List<AllocationResult> results = new ArrayList<>();
        for (UUID employeeId : req.getEmployeeIds()) {
            try {
                Employee employee = findEmployee(employeeId);
                ProjectAllocation allocation = validateAndBuild(employee.getUserId(), project,
                        req.getCapacityPercent(), req.getStartDate(), req.getEndDate());
                allocationRepository.save(allocation);
                results.add(AllocationResult.success(employeeId));
            } catch (RuntimeException e) {
                results.add(AllocationResult.failed(employeeId, e.getMessage()));
            }
        }
        return BulkAllocationResponse.builder().results(results).build();
    }

    private ProjectAllocation validateAndBuild(UUID employeeUserId, Project project, Integer capacityPercent,
                                                LocalDate startDate, LocalDate endDate) {
        if (!project.isActive()) {
            throw new IllegalArgumentException("Project \"" + project.getName() + "\" is not active");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        int alreadyAllocated = allocationRepository.findOverlapping(employeeUserId, startDate, endDate).stream()
                .mapToInt(ProjectAllocation::getCapacityPercent)
                .sum();
        if (alreadyAllocated + capacityPercent > 100) {
            throw new IllegalArgumentException(
                    "Capacity exceeds 100% for the given date range (already allocated " + alreadyAllocated + "%)");
        }
        return ProjectAllocation.builder()
                .employeeUserId(employeeUserId)
                .projectId(project.getId())
                .capacityPercent(capacityPercent)
                .startDate(startDate)
                .endDate(endDate)
                .build();
    }

    /**
     * US-B05 team capacity view, extended by US-B13 with optional {@code departmentId}/{@code
     * projectId} filters that combine with AND. Visibility is resolved exactly once, up front, via
     * {@link DirectReportScopeService} (the same hierarchy query Leave approvals is built on) as a
     * set of allowed employee ids, which {@code projectId} can only narrow further by intersection
     * — never widen, never replace — so a Manager can never see outside their own direct reports
     * no matter which filters are supplied. Only HR_ADMIN/SUPER_ADMIN get no such set at all.
     */
    @Transactional(readOnly = true)
    public List<EmployeeCapacityResponse> capacityView(String actorEmail, UUID departmentId, UUID projectId) {
        User actor = resolveUser(actorEmail);

        // null = unrestricted (HR_ADMIN/SUPER_ADMIN only); any other value is the hard ceiling
        // every further filter gets intersected into, never unioned or bypassed.
        Set<UUID> allowedIds = null;
        if (!hasVisibilityOverride(actor)) {
            allowedIds = directReportScopeService.directReportIds(actorEmail);
            if (allowedIds.isEmpty()) {
                return List.of();
            }
        }

        if (projectId != null) {
            Set<UUID> projectEmployeeIds = new HashSet<>(allocationRepository.findDistinctEmployeeUserIdsByProjectId(projectId));
            allowedIds = allowedIds == null ? projectEmployeeIds : intersect(allowedIds, projectEmployeeIds);
            if (allowedIds.isEmpty()) {
                return List.of();
            }
        }

        List<Employee> employees = allowedIds != null
                ? employeeRepository.findAllById(allowedIds)
                : employeeRepository.findAll();
        employees = employees.stream()
                .filter(e -> e.getUser() == null || e.getUser().getDeletedAt() == null)
                .filter(e -> departmentId == null || (e.getDepartment() != null && departmentId.equals(e.getDepartment().getId())))
                .toList();
        if (employees.isEmpty()) {
            return List.of();
        }

        List<UUID> employeeIds = employees.stream().map(Employee::getUserId).toList();
        List<ProjectAllocation> allocations = allocationRepository.findByEmployeeUserIdIn(employeeIds);
        Map<UUID, List<ProjectAllocation>> allocationsByEmployee = allocations.stream()
                .collect(Collectors.groupingBy(ProjectAllocation::getEmployeeUserId));
        Map<UUID, Project> projectsById = projectRepository
                .findAllById(allocations.stream().map(ProjectAllocation::getProjectId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Project::getId, p -> p));

        return employees.stream()
                .map(e -> toCapacityResponse(e, allocationsByEmployee.getOrDefault(e.getUserId(), List.of()), projectsById))
                .toList();
    }

    private EmployeeCapacityResponse toCapacityResponse(Employee e, List<ProjectAllocation> allocations,
                                                          Map<UUID, Project> projectsById) {
        List<EmployeeCapacityResponse.AllocationSummary> summaries = allocations.stream()
                .map(a -> EmployeeCapacityResponse.AllocationSummary.builder()
                        .projectId(a.getProjectId())
                        .projectName(Optional.ofNullable(projectsById.get(a.getProjectId())).map(Project::getName).orElse(null))
                        .capacityPercent(a.getCapacityPercent())
                        .startDate(a.getStartDate())
                        .endDate(a.getEndDate())
                        .build())
                .toList();
        return EmployeeCapacityResponse.builder()
                .employeeId(e.getUserId())
                .employeeName(e.getFullName())
                .departmentId(e.getDepartment() != null ? e.getDepartment().getId() : null)
                .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                .totalCapacityPercent(allocations.stream().mapToInt(ProjectAllocation::getCapacityPercent).sum())
                .allocations(summaries)
                .build();
    }

    private static Set<UUID> intersect(Set<UUID> a, Set<UUID> b) {
        Set<UUID> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    private boolean hasVisibilityOverride(User actor) {
        return actor.getRoles().stream().anyMatch(r -> VISIBILITY_OVERRIDE_ROLES.contains(r.getCode()));
    }

    private User resolveUser(String actorEmail) {
        return userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Employee findEmployee(UUID employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
    }

    private Project findProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("Project not found"));
    }

    private ProjectAllocationResponse toResponse(ProjectAllocation a, Employee employee, Project project) {
        return ProjectAllocationResponse.builder()
                .id(a.getId())
                .employeeId(employee.getUserId())
                .employeeName(employee.getFullName())
                .projectId(project.getId())
                .projectName(project.getName())
                .capacityPercent(a.getCapacityPercent())
                .startDate(a.getStartDate())
                .endDate(a.getEndDate())
                .build();
    }
}
