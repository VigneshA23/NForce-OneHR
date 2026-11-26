package com.nforce.onehr.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nforce.onehr.entity.AuditLog;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.AuditLogRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * YE-07: visibility of the nightly leave jobs (monthly crediting, {@link LeaveAccrualService}, and
 * year-end carry-forward/expiry, {@link LeaveYearEndService}). Every run — scheduled or "Run now" —
 * writes exactly one audit entry with its counts and the codes of any employees that failed; when
 * anything failed, every active Super Admin gets an in-app alert listing them. The latest entry of
 * each job is its "last run" status, shown under Org Setup → Leave.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveJobMonitor {

    public static final String ACCRUAL = "LEAVE_ACCRUAL_PROCESSED";
    public static final String YEAR_END = "LEAVE_YEAR_END_PROCESSED";
    public static final String FAILURE_NOTIFICATION = "LEAVE_JOB_FAILED";
    private static final int MAX_CODES_IN_ALERT = 20;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditService auditService;
    private final AuditSnapshotSerializer auditSnapshot;
    private final AuditLogRepository auditLogRepository;
    private final EmployeeRepository employeeRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** The last run of one job: when, who (null = the nightly scheduler) and what it did. */
    public record JobRun(String job, Instant ranAt, UUID actorId, String actorName, Map<String, Object> details) {}

    /**
     * Records one run. {@code details} are the job's own counts; the failed employees are added as
     * codes ({@code failedEmployees}). Returns those codes, in the order the employees failed.
     */
    public List<String> recordRun(String action, String jobLabel, UUID actorId, Map<String, Object> details,
                                  Collection<UUID> failedEmployeeIds) {
        List<String> codes = codesOf(failedEmployeeIds);
        Map<String, Object> after = new LinkedHashMap<>(details);
        after.put("failedEmployees", codes);
        auditService.log(actorId, action, null, null, auditSnapshot.toJson(after));
        if (!codes.isEmpty()) alertSuperAdmins(jobLabel, codes);
        return codes;
    }

    private void alertSuperAdmins(String jobLabel, List<String> codes) {
        String shown = String.join(", ", codes.subList(0, Math.min(codes.size(), MAX_CODES_IN_ALERT)))
                + (codes.size() > MAX_CODES_IN_ALERT ? " and " + (codes.size() - MAX_CODES_IN_ALERT) + " more" : "");
        String message = jobLabel + " failed for " + codes.size() + " employee" + (codes.size() == 1 ? "" : "s") + ": " + shown
                + ". The others were processed. It is retried automatically every night, or use Run now under Organization Masters → Leave.";
        for (User admin : userRepository.findActiveSuperAdmins()) {
            notificationService.send(admin.getId(), FAILURE_NOTIFICATION, "Leave job needs attention", message, "/organization");
        }
    }

    /** Employee codes for these user ids (the id itself when an employee record has no code). */
    List<String> codesOf(Collection<UUID> userIds) {
        Set<UUID> ids = new LinkedHashSet<>(userIds);
        if (ids.isEmpty()) return List.of();
        Map<UUID, String> byId = new HashMap<>();
        for (Object[] row : employeeRepository.findCodesByUserIds(ids)) {
            byId.put((UUID) row[0], (String) row[1]);
        }
        List<String> out = new ArrayList<>();
        for (UUID id : ids) out.add(byId.getOrDefault(id, id.toString()));
        return out;
    }

    /** The latest run of each job, keyed "accrual" / "yearEnd"; a job that never ran is absent. */
    @Transactional(readOnly = true)
    public Map<String, JobRun> lastRuns() {
        Map<String, JobRun> out = new LinkedHashMap<>();
        lastRun(ACCRUAL).ifPresent(r -> out.put("accrual", r));
        lastRun(YEAR_END).ifPresent(r -> out.put("yearEnd", r));
        return out;
    }

    private java.util.Optional<JobRun> lastRun(String action) {
        return auditLogRepository.findByActionInOrderByOccurredAtDesc(List.of(action), PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(a -> new JobRun(action, a.getOccurredAt(), a.getActorId(), actorName(a), details(a)));
    }

    private String actorName(AuditLog a) {
        if (a.getActorId() == null) return null;
        return employeeRepository.findById(a.getActorId())
                .map(com.nforce.onehr.entity.Employee::getFullName)
                .orElseGet(() -> userRepository.findById(a.getActorId()).map(User::getEmail).orElse(null));
    }

    private static Map<String, Object> details(AuditLog a) {
        if (a.getAfterState() == null) return Map.of();
        try {
            return JSON.readValue(a.getAfterState(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Unreadable leave job audit entry {}: {}", a.getId(), e.getMessage());
            return Map.of();
        }
    }
}
