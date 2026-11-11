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
import com.nforce.onehr.entity.ProjectStatus;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ProjectAllocationRepository;
import com.nforce.onehr.repository.ProjectRepository;
import com.nforce.onehr.repository.UserRepository;
import com.nforce.onehr.security.EffectivePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nforce.onehr.dto.allocation.ProjectAllocationUpdateRequest;

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
 * Single source of truth for project-allocation validation (US-B04 create / US-B06 over-allocation
 * warning): {@link #validate} holds the hard rules, {@link #allocatedCapacity} is the one capacity
 * aggregation behind the soft over-100% warning, and {@link #requireCanAllocate} (US-B03) the one
 * place that decides who may allocate.
 * {@link #allocate} (single) and {@link #bulkAllocate} (US-B10) both call it instead of
 * re-implementing the rule — do not duplicate this logic in a new allocation path.
 *
 * <p>{@link #capacityView} (US-B05, filterable by {@link #capacityView} since US-B13) is the team
 * capacity read path. Its visibility is deliberately the same hierarchy rule {@link LeaveService}
 * uses for approvals — {@link DirectReportScopeService}'s direct-report set for a plain Manager,
 * unrestricted only for holders of project_allocations:VIEW:ALL (HR_ADMIN/SUPER_ADMIN by default) —
 * so a Manager's department/project filters can only
 * narrow that set further, never substitute for it.
 */
@Service
@RequiredArgsConstructor
public class ProjectAllocationService {

    private final ProjectAllocationRepository allocationRepository;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final DirectReportScopeService directReportScopeService;
    private final EffectivePermissionService effectivePermissionService;

    @Transactional
    public ProjectAllocationResponse allocate(String actorEmail, ProjectAllocationRequest req) {
        Project project = findProject(req.getProjectId());
        requireCanAllocate(resolveUser(actorEmail), project);
        Employee employee = findEmployee(req.getEmployeeId());
        validate(project, req.getStartDate(), req.getEndDate());
        int projected = allocatedCapacity(employee.getUserId(), req.getStartDate(), req.getEndDate()) + req.getCapacityPercent();
        String warning = overAllocationWarning(projected);
        ProjectAllocation allocation = build(employee.getUserId(), project, req.getCapacityPercent(),
                req.getStartDate(), req.getEndDate());
        // US-B06 soft block: over 100% is only persisted once the caller confirms; otherwise id stays null.
        if (warning == null || req.isConfirm()) {
            allocation = allocationRepository.save(allocation);
        }
        return toResponse(allocation, employee, project, projected, warning);
    }

    /**
     * US-B04/B05/B06/B10: the one capacity aggregation — total capacity% of the employee's active
     * allocations overlapping [startDate, endDate]. Every capacity check must go through here.
     * ponytail: sums every overlapping row even if those rows don't overlap each other (over-reports
     * for a long range); switch to a per-day peak if that produces false warnings in practice.
     */
    public int allocatedCapacity(UUID employeeUserId, LocalDate startDate, LocalDate endDate) {
        return (int) allocationRepository.sumOverlappingCapacity(employeeUserId, startDate, endDate);
    }

    private static String overAllocationWarning(int projectedPercent) {
        return projectedPercent > 100
                ? "Employee would be allocated " + projectedPercent + "% for this date range (over 100%)"
                : null;
    }

    /**
     * US-B03: edit or release (actualEndDate) an allocation. The ownership gate runs against the
     * allocation's own project, so a Manager can only touch allocations on projects they own.
     */
    @Transactional
    public ProjectAllocationResponse update(String actorEmail, UUID allocationId, ProjectAllocationUpdateRequest req) {
        ProjectAllocation allocation = allocationRepository.findById(allocationId)
                .orElseThrow(() -> new NoSuchElementException("Allocation not found"));
        Project project = findProject(allocation.getProjectId());
        requireCanAllocate(resolveUser(actorEmail), project);
        if (req.getCapacityPercent() != null) allocation.setCapacityPercent(req.getCapacityPercent());
        if (req.getEndDate() != null) allocation.setEndDate(req.getEndDate());
        if (req.getActualEndDate() != null) allocation.setActualEndDate(req.getActualEndDate());
        LocalDate end = allocation.getEndDate();
        if (end.isBefore(allocation.getStartDate())
                || (allocation.getActualEndDate() != null && allocation.getActualEndDate().isBefore(allocation.getStartDate()))) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        Employee employee = findEmployee(allocation.getEmployeeUserId());
        return toResponse(allocationRepository.save(allocation), employee, project,
                allocatedCapacity(employee.getUserId(), allocation.getStartDate(), end), null);
    }

    /** US-B10: project/capacity/dates are shared across the request; each employee is still validated on its own. */
    @Transactional
    public BulkAllocationResponse bulkAllocate(String actorEmail, BulkProjectAllocationRequest req) {
        Project project = findProject(req.getProjectId());
        // Whole-request gate, not a per-employee failure: the caller either owns the project or doesn't.
        requireCanAllocate(resolveUser(actorEmail), project);
        List<AllocationResult> results = new ArrayList<>();
        for (UUID employeeId : req.getEmployeeIds()) {
            try {
                Employee employee = findEmployee(employeeId);
                validate(project, req.getStartDate(), req.getEndDate());
                String warning = overAllocationWarning(
                        allocatedCapacity(employee.getUserId(), req.getStartDate(), req.getEndDate()) + req.getCapacityPercent());
                if (warning != null && !req.isConfirm()) {
                    results.add(AllocationResult.warning(employeeId, warning));
                    continue;
                }
                allocationRepository.save(build(employee.getUserId(), project,
                        req.getCapacityPercent(), req.getStartDate(), req.getEndDate()));
                results.add(AllocationResult.success(employeeId));
            } catch (RuntimeException e) {
                results.add(AllocationResult.failed(employeeId, e.getMessage()));
            }
        }
        return BulkAllocationResponse.builder().results(results).build();
    }

    /**
     * US-B03: only the project's owner or a holder of project_allocations:CREATE:ALL
     * (HR_ADMIN/SUPER_ADMIN by default) may create or edit allocations against it. Enforced here rather than in the UI, so every allocation path (single, bulk,
     * opportunity close) is covered — any future allocation-edit path must call this too.
     */
    private void requireCanAllocate(User actor, Project project) {
        if (effectivePermissionService.hasAuthority(actor, "project_allocations:CREATE:ALL")) return;
        if (project.getOwnerEmployeeId() == null || !project.getOwnerEmployeeId().equals(actor.getId())) {
            throw new AccessDeniedException(
                    "Only the project owner or HR can allocate resources to \"" + project.getName() + "\"");
        }
    }

    /** Hard rules (4xx). Over-allocation is deliberately NOT here — it's a soft warning (US-B06). */
    private static void validate(Project project, LocalDate startDate, LocalDate endDate) {
        // An expired project is closed even if the daily sweep hasn't flipped its status yet.
        boolean expired = project.getEndDate() != null && project.getEndDate().isBefore(LocalDate.now());
        if (project.getStatus() != ProjectStatus.ACTIVE || expired) {
            throw new IllegalArgumentException("Project \"" + project.getName() + "\" is not active");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
    }

    private static ProjectAllocation build(UUID employeeUserId, Project project, Integer capacityPercent,
                                           LocalDate startDate, LocalDate endDate) {
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
     * no matter which filters are supplied. Only project_allocations:VIEW:ALL holders get no such set at all.
     */
    @Transactional(readOnly = true)
    public List<EmployeeCapacityResponse> capacityView(String actorEmail, UUID departmentId, UUID projectId) {
        User actor = resolveUser(actorEmail);

        // null = unrestricted (project_allocations:VIEW:ALL only); any other value is the hard ceiling
        // every further filter gets intersected into, never unioned or bypassed.
        Set<UUID> allowedIds = null;
        if (!effectivePermissionService.hasAuthority(actor, "project_allocations:VIEW:ALL")) {
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

    private ProjectAllocationResponse toResponse(ProjectAllocation a, Employee employee, Project project,
                                                 int projectedCapacityPercent, String warning) {
        return ProjectAllocationResponse.builder()
                .id(a.getId())
                .employeeId(employee.getUserId())
                .employeeName(employee.getFullName())
                .projectId(project.getId())
                .projectName(project.getName())
                .capacityPercent(a.getCapacityPercent())
                .startDate(a.getStartDate())
                .endDate(a.getEndDate())
                .actualEndDate(a.getActualEndDate())
                .projectedCapacityPercent(projectedCapacityPercent)
                .warning(warning)
                .build();
    }
}
