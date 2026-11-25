package com.nforce.onehr.service;

import com.nforce.onehr.dto.bench.BenchStatusRequest;
import com.nforce.onehr.dto.bench.BenchStatusResponse;
import com.nforce.onehr.dto.bench.EndBenchRequest;
import com.nforce.onehr.dto.bench.TeamBenchResponse;
import com.nforce.onehr.entity.BenchCloseReason;
import com.nforce.onehr.entity.BenchStatus;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectAllocation;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.BenchStatusRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.ProjectAllocationRepository;
import com.nforce.onehr.repository.ProjectRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-A01 (mark/end bench status, audited) and US-A02 (a Manager's benched direct reports).
 *
 * <p>The team read is scoped by {@link DirectReportScopeService} <em>before</em> any bench row is
 * loaded — the same hierarchy rule Leave approvals and the project capacity view use — and, like
 * that service, never widens for HR_ADMIN/SUPER_ADMIN: the organisation-wide view is the
 * dashboard's job (US-A03), not this endpoint's.
 */
@Service
@RequiredArgsConstructor
public class BenchStatusService {

    // target_id is the affected employee (the audit trail's convention, so the Audit page can show a name);
    // the bench record's own id travels in the before/after snapshot as benchStatusId.
    static final String AUDIT_ACTION = "BENCH_STATUS_CHANGE";

    private final BenchStatusRepository benchRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final ProjectAllocationRepository allocationRepository;
    private final ProjectRepository projectRepository;
    private final DirectReportScopeService directReportScopeService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer snapshotSerializer;
    private final BenchAgingService agingService;

    @Transactional
    public BenchStatusResponse start(String actorEmail, BenchStatusRequest req) {
        User actor = resolveUser(actorEmail);
        Employee employee = findEmployee(req.getEmployeeId());
        if (benchRepository.existsByEmployeeUserIdAndActiveTrue(employee.getUserId())) {
            throw new IllegalStateException("Employee is already on bench");
        }
        LocalDate start = req.getStartDate() != null ? req.getStartDate() : LocalDate.now();
        BenchStatus saved = benchRepository.save(BenchStatus.builder()
                .employeeUserId(employee.getUserId())
                .reasonCode(req.getReasonCode())
                .startDate(start)
                .agingBucket(agingService.bucketFor(Math.max(0, ChronoUnit.DAYS.between(start, LocalDate.now()))))
                .build());
        String after = snapshot(saved);
        AuditService.afterCommit(() -> auditService.log(actor.getId(), AUDIT_ACTION, saved.getEmployeeUserId(), null, after));
        // ONEHR-729: a start date back-dated past the threshold notifies the manager now, not at 00:15.
        agingService.notifyThresholdCrossings(List.of(saved), LocalDate.now());
        return toResponse(saved, employee);
    }

    /** Employees who can be marked on bench: active and not already on bench. Gated by bench permission, not employees:VIEW. */
    @Transactional(readOnly = true)
    public List<com.nforce.onehr.dto.bench.BenchDtos.EligibleEmployee> eligibleEmployees() {
        Set<UUID> benched = benchRepository.findByActiveTrue().stream()
                .map(BenchStatus::getEmployeeUserId).collect(Collectors.toSet());
        return employeeRepository.findAllWithDetails().stream()
                .filter(e -> e.getUser().isActive() && !benched.contains(e.getUserId()))
                .sorted(Comparator.comparing(Employee::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(e -> new com.nforce.onehr.dto.bench.BenchDtos.EligibleEmployee(e.getUserId(), e.getFullName(), e.getEmployeeCode()))
                .toList();
    }

    @Transactional
    public BenchStatusResponse end(String actorEmail, UUID benchStatusId, EndBenchRequest req) {
        User actor = resolveUser(actorEmail);
        BenchStatus bench = benchRepository.findById(benchStatusId)
                .orElseThrow(() -> new NoSuchElementException("Bench status not found"));
        return close(actor.getId(), bench, req);
    }

    /**
     * US-A12 seam: ends this employee's active episode because they were just allocated. The same
     * {@link #close} path as a manual end (US-A06), so validation and auditing never diverge, and it
     * joins the caller's transaction so a later failure rolls the bench end back too.
     */
    @Transactional
    public BenchStatusResponse endForAllocation(UUID actorUserId, UUID employeeUserId, UUID allocationId, LocalDate endDate) {
        BenchStatus bench = benchRepository.findByEmployeeUserIdAndActiveTrue(employeeUserId)
                .orElseThrow(() -> new IllegalStateException("Employee is not on bench"));
        EndBenchRequest req = new EndBenchRequest();
        req.setAllocationId(allocationId);
        // The allocation may start before the bench episode did (back-dated); clamp so the episode
        // never ends before it began rather than rejecting a legitimate assignment.
        req.setEndDate(endDate != null && endDate.isBefore(bench.getStartDate()) ? bench.getStartDate() : endDate);
        return close(actorUserId, bench, req);
    }

    /**
     * US-A06: ends an episode (never deletes it — start/end/duration stay for reporting). Without an
     * allocation a close-out reason is mandatory; with one it must exist and belong to this employee.
     */
    private BenchStatusResponse close(UUID actorId, BenchStatus bench, EndBenchRequest req) {
        if (!bench.isActive()) {
            throw new IllegalStateException("Bench status has already ended");
        }
        if (req.getAllocationId() != null) {
            ProjectAllocation allocation = allocationRepository.findById(req.getAllocationId())
                    .orElseThrow(() -> new NoSuchElementException("Allocation not found"));
            if (!allocation.getEmployeeUserId().equals(bench.getEmployeeUserId())) {
                throw new IllegalArgumentException("Allocation belongs to a different employee");
            }
        } else if (req.getCloseReason() == null) {
            throw new IllegalArgumentException("A close-out reason is required when ending bench without an allocation");
        } else if (req.getCloseReason() == BenchCloseReason.OTHER
                && (req.getCloseNote() == null || req.getCloseNote().isBlank())) {
            // "Other" says nothing on its own, so a report reader needs the note to know why bench ended.
            throw new IllegalArgumentException("A note is required when the close reason is Other");
        }
        LocalDate endDate = req.getEndDate() != null ? req.getEndDate() : LocalDate.now();
        if (endDate.isBefore(bench.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        String before = snapshot(bench);
        bench.setEndDate(endDate);
        bench.setActive(false);
        bench.setAllocationId(req.getAllocationId());
        bench.setCloseReason(req.getAllocationId() == null ? req.getCloseReason() : null);
        bench.setCloseNote(req.getCloseNote());
        BenchStatus saved = benchRepository.save(bench);
        String after = snapshot(saved);
        AuditService.afterCommit(() -> auditService.log(actorId, AUDIT_ACTION, saved.getEmployeeUserId(), before, after));
        return toResponse(saved, findEmployee(saved.getEmployeeUserId()));
    }

    /** US-A06 AC-2: every ended episode with its duration, for reporting. Two batched lookups, no per-row queries. */
    @Transactional(readOnly = true)
    public List<com.nforce.onehr.dto.bench.BenchDtos.BenchHistoryRow> history() {
        List<BenchStatus> ended = benchRepository.findByActiveFalseOrderByEndDateDescStartDateDesc();
        if (ended.isEmpty()) {
            return List.of();
        }
        Map<UUID, Employee> employees = employeeRepository
                .findAllById(ended.stream().map(BenchStatus::getEmployeeUserId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));
        Map<UUID, ProjectAllocation> allocations = allocationRepository
                .findAllById(ended.stream().map(BenchStatus::getAllocationId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ProjectAllocation::getId, a -> a));
        Map<UUID, String> projectNames = projectRepository
                .findAllById(allocations.values().stream().map(ProjectAllocation::getProjectId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Project::getId, Project::getName));
        return ended.stream().map(b -> {
            Employee e = employees.get(b.getEmployeeUserId());
            ProjectAllocation a = b.getAllocationId() == null ? null : allocations.get(b.getAllocationId());
            return new com.nforce.onehr.dto.bench.BenchDtos.BenchHistoryRow(b.getId(), b.getEmployeeUserId(),
                    e == null ? null : e.getFullName(), b.getReasonCode(), b.getStartDate(), b.getEndDate(),
                    durationDays(b), b.getCloseReason(), b.getCloseNote(),
                    a == null ? null : projectNames.get(a.getProjectId()));
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<TeamBenchResponse> teamBench(String actorEmail) {
        Set<UUID> reportIds = directReportScopeService.directReportIds(actorEmail);
        if (reportIds.isEmpty()) {
            return List.of();
        }
        List<BenchStatus> benched = benchRepository.findByActiveTrueAndEmployeeUserIdIn(reportIds);
        if (benched.isEmpty()) {
            return List.of();
        }
        List<UUID> benchedIds = benched.stream().map(BenchStatus::getEmployeeUserId).toList();
        Map<UUID, Employee> employees = employeeRepository.findAllById(benchedIds).stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));
        Map<UUID, String> lastProject = lastProjectNames(benchedIds);
        LocalDate today = LocalDate.now();

        return benched.stream()
                .map(b -> TeamBenchResponse.builder()
                        .employeeId(b.getEmployeeUserId())
                        .employeeName(employees.containsKey(b.getEmployeeUserId())
                                ? employees.get(b.getEmployeeUserId()).getFullName() : null)
                        .reasonCode(b.getReasonCode())
                        .startDate(b.getStartDate())
                        .daysOnBench(Math.max(0, ChronoUnit.DAYS.between(b.getStartDate(), today)))
                        .lastProject(lastProject.get(b.getEmployeeUserId()))
                        .build())
                .sorted(Comparator.comparingLong(TeamBenchResponse::getDaysOnBench).reversed())
                .toList();
    }

    /** One query for the latest-ending allocation per employee, one for the project names. */
    private Map<UUID, String> lastProjectNames(List<UUID> employeeIds) {
        Map<UUID, ProjectAllocation> latest = new LinkedHashMap<>();
        for (ProjectAllocation a : allocationRepository.findLatestEndingForEmployees(employeeIds)) {
            latest.putIfAbsent(a.getEmployeeUserId(), a);
        }
        Map<UUID, String> projectNames = projectRepository
                .findAllById(latest.values().stream().map(ProjectAllocation::getProjectId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Project::getId, Project::getName));
        Map<UUID, String> result = new HashMap<>();
        latest.forEach((employeeId, a) -> result.put(employeeId, projectNames.get(a.getProjectId())));
        return result;
    }

    private String snapshot(BenchStatus b) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("benchStatusId", b.getId());
        fields.put("employeeId", b.getEmployeeUserId());
        fields.put("reasonCode", b.getReasonCode());
        fields.put("startDate", b.getStartDate());
        fields.put("endDate", b.getEndDate());
        fields.put("durationDays", durationDays(b));
        fields.put("active", b.isActive());
        fields.put("closeReason", b.getCloseReason());
        fields.put("closeNote", b.getCloseNote());
        fields.put("allocationId", b.getAllocationId());
        return snapshotSerializer.toJson(fields);
    }

    /** US-A06 AC-2: days from bench start to bench end; null while the episode is still open. */
    private static Long durationDays(BenchStatus b) {
        return b.getEndDate() == null ? null : ChronoUnit.DAYS.between(b.getStartDate(), b.getEndDate());
    }

    private BenchStatusResponse toResponse(BenchStatus b, Employee employee) {
        return BenchStatusResponse.builder()
                .id(b.getId())
                .employeeId(b.getEmployeeUserId())
                .employeeName(employee.getFullName())
                .reasonCode(b.getReasonCode())
                .startDate(b.getStartDate())
                .endDate(b.getEndDate())
                .durationDays(durationDays(b))
                .active(b.isActive())
                .agingBucket(b.getAgingBucket())
                .closeReason(b.getCloseReason())
                .allocationId(b.getAllocationId())
                .closeNote(b.getCloseNote())
                .build();
    }

    private User resolveUser(String actorEmail) {
        return userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
    }

    private Employee findEmployee(UUID employeeId) {
        return employeeRepository.findById(employeeId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found"));
    }
}
