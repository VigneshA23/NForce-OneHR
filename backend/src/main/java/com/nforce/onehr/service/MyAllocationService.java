package com.nforce.onehr.service;

import com.nforce.onehr.dto.allocation.MyAllocationResponse;
import com.nforce.onehr.dto.allocation.MyAllocationResponse.Status;
import com.nforce.onehr.entity.Client;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectAllocation;
import com.nforce.onehr.repository.ClientRepository;
import com.nforce.onehr.repository.ProjectAllocationRepository;
import com.nforce.onehr.repository.ProjectRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * US-B08 — an employee's read-only view of their OWN current and upcoming project allocations.
 *
 * <p>The employee is resolved from the authenticated principal only; there is no employee id input
 * anywhere, so one employee can never ask for another's allocations. Reads only — nothing here
 * creates, edits or deletes an allocation (those stay in {@link ProjectAllocationService}, behind
 * the Manager/HR permissions of US-B03/US-B04).
 *
 * <p>Classification, with "today" taken in the org's business timezone (the same
 * {@link AttendanceRulesService#getDefaultZoneId()} Leave and the AI data providers use):
 * <ul>
 *   <li>CURRENT — start &lt;= today &lt;= effective end</li>
 *   <li>UPCOMING — start &gt; today</li>
 *   <li>excluded — effective end &lt; today (already ended). Effective end is {@code actualEndDate}
 *       when an allocation was released early, else {@code endDate}.</li>
 * </ul>
 * Project and client are joined by batch id lookups (the allocation entity stores raw ids). A missing
 * project or an untagged/missing client yields null names, never an error.
 */
@Service
@RequiredArgsConstructor
public class MyAllocationService {

    private final ProjectAllocationRepository allocationRepository;
    private final ProjectRepository projectRepository;
    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final AttendanceRulesService attendanceRulesService;

    @Transactional(readOnly = true)
    public List<MyAllocationResponse> myCurrentAndUpcoming(String actorEmail) {
        return myCurrentAndUpcoming(actorEmail, LocalDate.now(attendanceRulesService.getDefaultZoneId()));
    }

    List<MyAllocationResponse> myCurrentAndUpcoming(String actorEmail, LocalDate today) {
        UUID employeeId = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"))
                .getId();

        // The query already drops ended allocations; the filter below is the same rule applied in Java so
        // the contract holds regardless of what the repository hands back.
        List<ProjectAllocation> allocations = allocationRepository.findCurrentAndUpcoming(employeeId, today).stream()
                .filter(a -> !effectiveEnd(a).isBefore(today))
                .toList();
        if (allocations.isEmpty()) {
            return List.of();
        }

        Map<UUID, Project> projectsById = projectRepository
                .findAllById(allocations.stream().map(ProjectAllocation::getProjectId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Project::getId, Function.identity()));
        Map<UUID, Client> clientsById = clientRepository
                .findAllById(projectsById.values().stream().map(Project::getClientId)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Client::getId, Function.identity()));

        return allocations.stream()
                .map(a -> toResponse(a, projectsById.get(a.getProjectId()), clientsById, today))
                .sorted(Comparator.comparing(MyAllocationResponse::getStatus)               // CURRENT before UPCOMING
                        .thenComparing(MyAllocationResponse::getStartDate)
                        .thenComparing(r -> r.getProjectName() == null ? "" : r.getProjectName(),
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static LocalDate effectiveEnd(ProjectAllocation a) {
        return a.getActualEndDate() != null ? a.getActualEndDate() : a.getEndDate();
    }

    private static MyAllocationResponse toResponse(ProjectAllocation a, Project project,
                                                   Map<UUID, Client> clientsById, LocalDate today) {
        Client client = project != null && project.getClientId() != null ? clientsById.get(project.getClientId()) : null;
        return MyAllocationResponse.builder()
                .id(a.getId())
                .projectName(project != null ? project.getName() : null)
                .clientName(client != null ? client.getName() : null)
                .capacityPercent(a.getCapacityPercent())
                .startDate(a.getStartDate())
                .endDate(effectiveEnd(a))
                .status(a.getStartDate().isAfter(today) ? Status.UPCOMING : Status.CURRENT)
                .build();
    }
}
