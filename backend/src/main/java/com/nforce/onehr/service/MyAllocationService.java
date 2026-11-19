package com.nforce.onehr.service;

import com.nforce.onehr.dto.allocation.MyAllocationResponse;
import com.nforce.onehr.dto.allocation.MyAllocationResponse.Status;
import com.nforce.onehr.entity.Client;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectAllocation;
import com.nforce.onehr.entity.ProjectStatus;
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
 *
 * <p>The project's status travels with each allocation ({@code projectStatus}): closing a project
 * deliberately leaves its allocation rows untouched, so a closed project's allocation is still returned,
 * labelled CLOSED, and sorted after the ACTIVE ones (then ON_HOLD, then CLOSED; within a status, CURRENT
 * before UPCOMING, then by start date). An ACTIVE project whose end date has passed already counts as
 * CLOSED, since the daily auto-close may not have run yet.
 *
 * <p>The dates shown follow the project's current dates, as a manager sees them on the Projects screen
 * (open or closed): the allocation's range is clipped to the project's window, or the project's own
 * window is shown when the two no longer overlap. See {@link #displayedRange}.
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

        // A closed project's allocation is NOT dropped (closing a project deliberately leaves its allocation
        // rows alone) — it is shown labelled with the project's status, after the active ones.
        return allocations.stream()
                .map(a -> toResponse(a, projectsById.get(a.getProjectId()), clientsById, today))
                .sorted(Comparator.comparingInt((MyAllocationResponse r) -> projectRank(r.getProjectStatus())) // ACTIVE, ON_HOLD, CLOSED
                        .thenComparing(MyAllocationResponse::getStatus)                                       // CURRENT before UPCOMING
                        .thenComparing(MyAllocationResponse::getStartDate)
                        .thenComparing(r -> r.getProjectName() == null ? "" : r.getProjectName(),
                                String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static LocalDate effectiveEnd(ProjectAllocation a) {
        return a.getActualEndDate() != null ? a.getActualEndDate() : a.getEndDate();
    }

    /** The dates to show on the card. */
    record DateRange(LocalDate start, LocalDate end) {}

    /**
     * The dates to SHOW, kept in step with the project dates a manager sees on the Projects screen (a manager
     * can move a project's dates after allocations were made). The allocation's own range (its end being the
     * actualEndDate when released early) is clipped to the project's window; if the two do not overlap at all
     * — e.g. the project was edited to end before the allocation starts — the project's own window is shown,
     * exactly as the manager sees it. With no project end date the allocation's own dates are shown.
     * Display only: whether an allocation is listed, and Current vs Upcoming, still follow the allocation's
     * own dates, so a closed project's allocation stays visible.
     */
    static DateRange displayedRange(ProjectAllocation a, Project project) {
        LocalDate start = a.getStartDate();
        LocalDate end = effectiveEnd(a);
        if (project == null || project.getEndDate() == null) {
            return new DateRange(start, end);
        }
        LocalDate projectStart = project.getStartDate();
        LocalDate projectEnd = project.getEndDate();
        LocalDate clippedStart = projectStart != null && projectStart.isAfter(start) ? projectStart : start;
        LocalDate clippedEnd = projectEnd.isBefore(end) ? projectEnd : end;
        if (!clippedStart.isAfter(clippedEnd)) {
            return new DateRange(clippedStart, clippedEnd);
        }
        return projectStart != null && !projectStart.isAfter(projectEnd)
                ? new DateRange(projectStart, projectEnd)
                : new DateRange(start, end);
    }

    /**
     * The project's status as it should be shown today. An ACTIVE project whose end date has already
     * passed counts as CLOSED, because the daily sweep that actually flips it may not have run yet.
     * Null when the project row is missing.
     */
    static ProjectStatus effectiveProjectStatus(Project project, LocalDate today) {
        if (project == null) {
            return null;
        }
        if (project.getStatus() == ProjectStatus.ACTIVE && project.getEndDate() != null
                && project.getEndDate().isBefore(today)) {
            return ProjectStatus.CLOSED;
        }
        return project.getStatus();
    }

    /** Active projects first, then on-hold, then closed. An unknown (missing) project sorts with the active ones. */
    static int projectRank(ProjectStatus status) {
        if (status == ProjectStatus.CLOSED) {
            return 2;
        }
        return status == ProjectStatus.ON_HOLD ? 1 : 0;
    }

    private static MyAllocationResponse toResponse(ProjectAllocation a, Project project,
                                                   Map<UUID, Client> clientsById, LocalDate today) {
        Client client = project != null && project.getClientId() != null ? clientsById.get(project.getClientId()) : null;
        DateRange shown = displayedRange(a, project);
        return MyAllocationResponse.builder()
                .id(a.getId())
                .projectName(project != null ? project.getName() : null)
                .clientName(client != null ? client.getName() : null)
                .capacityPercent(a.getCapacityPercent())
                .startDate(shown.start())
                .endDate(shown.end())
                .projectStatus(effectiveProjectStatus(project, today))
                .status(a.getStartDate().isAfter(today) ? Status.UPCOMING : Status.CURRENT)
                .build();
    }
}
