package com.nforce.onehr.service;

import com.nforce.onehr.dto.bench.BenchStatusRequest;
import com.nforce.onehr.dto.bench.BenchStatusResponse;
import com.nforce.onehr.dto.bench.EndBenchRequest;
import com.nforce.onehr.dto.bench.TeamBenchResponse;
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

    static final String AUDIT_ACTION = "BENCH_STATUS_CHANGE";

    private final BenchStatusRepository benchRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final ProjectAllocationRepository allocationRepository;
    private final ProjectRepository projectRepository;
    private final DirectReportScopeService directReportScopeService;
    private final AuditService auditService;
    private final AuditSnapshotSerializer snapshotSerializer;

    @Transactional
    public BenchStatusResponse start(String actorEmail, BenchStatusRequest req) {
        User actor = resolveUser(actorEmail);
        Employee employee = findEmployee(req.getEmployeeId());
        if (benchRepository.existsByEmployeeUserIdAndActiveTrue(employee.getUserId())) {
            throw new IllegalStateException("Employee is already on bench");
        }
        BenchStatus saved = benchRepository.save(BenchStatus.builder()
                .employeeUserId(employee.getUserId())
                .reasonCode(req.getReasonCode())
                .startDate(req.getStartDate() != null ? req.getStartDate() : LocalDate.now())
                .build());
        auditService.log(actor.getId(), AUDIT_ACTION, saved.getId(), null, snapshot(saved));
        return toResponse(saved, employee);
    }

    @Transactional
    public BenchStatusResponse end(String actorEmail, UUID benchStatusId, EndBenchRequest req) {
        User actor = resolveUser(actorEmail);
        BenchStatus bench = benchRepository.findById(benchStatusId)
                .orElseThrow(() -> new NoSuchElementException("Bench status not found"));
        if (!bench.isActive()) {
            throw new IllegalStateException("Bench status has already ended");
        }
        LocalDate endDate = req != null && req.getEndDate() != null ? req.getEndDate() : LocalDate.now();
        if (endDate.isBefore(bench.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        String before = snapshot(bench);
        bench.setEndDate(endDate);
        bench.setActive(false);
        BenchStatus saved = benchRepository.save(bench);
        auditService.log(actor.getId(), AUDIT_ACTION, saved.getId(), before, snapshot(saved));
        return toResponse(saved, findEmployee(saved.getEmployeeUserId()));
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
        fields.put("employeeId", b.getEmployeeUserId());
        fields.put("reasonCode", b.getReasonCode());
        fields.put("startDate", b.getStartDate());
        fields.put("endDate", b.getEndDate());
        fields.put("active", b.isActive());
        return snapshotSerializer.toJson(fields);
    }

    private BenchStatusResponse toResponse(BenchStatus b, Employee employee) {
        return BenchStatusResponse.builder()
                .id(b.getId())
                .employeeId(b.getEmployeeUserId())
                .employeeName(employee.getFullName())
                .reasonCode(b.getReasonCode())
                .startDate(b.getStartDate())
                .endDate(b.getEndDate())
                .active(b.isActive())
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
