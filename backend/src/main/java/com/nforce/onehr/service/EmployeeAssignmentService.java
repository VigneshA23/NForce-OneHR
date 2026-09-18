package com.nforce.onehr.service;

import com.nforce.onehr.config.AttendanceProperties;
import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.dto.assignments.AssignmentLookupsResponse;
import com.nforce.onehr.dto.assignments.EmployeeAssignmentRow;
import com.nforce.onehr.dto.assignments.ImportResultResponse;
import com.nforce.onehr.dto.penalization.BulkAllocationRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeShiftAssignment;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.EmployeeShiftAssignmentRepository;
import com.nforce.onehr.repository.PenalisationPolicyRepository;
import com.nforce.onehr.repository.ShiftRepository;
import com.nforce.onehr.repository.WeeklyOffPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Manager: Bulk-Edit Team Shift, Weekly Off & Penalisation Policy Assignments (ONEHR-108).
 * Every write is re-scoped against the caller's *current* direct reports at call time — the
 * same re-verify-on-write pattern LeaveService/RegularizationService use for approvals.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeAssignmentService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository managerHistoryRepository;
    private final ShiftRepository shiftRepository;
    private final WeeklyOffPolicyRepository weeklyOffPolicyRepository;
    private final PenalisationPolicyRepository penalisationPolicyRepository;
    private final AuditService auditService;
    private final PenalizationPolicyAllocationService penalizationPolicyAllocationService;
    private final PenalizationPolicyResolutionService penalizationPolicyResolutionService;
    private final AttendanceProperties attendanceProperties;
    private final ShiftVersionResolver shiftVersionResolver;
    private final EmployeeShiftAssignmentRepository employeeShiftAssignmentRepository;
    // Resolves the CURRENTLY-effective assignment (never Employee.shift, a best-effort display
    // cache) — used by assignShift to find the same-date row a new assignment replaces in place,
    // and by listTeamAssignments for its own "Active since"/"Scheduled" display (see toRow).
    private final EmployeeShiftAssignmentResolver employeeShiftAssignmentResolver;

    @Transactional(readOnly = true)
    public List<EmployeeAssignmentRow> listTeamAssignments(String managerEmail, UUID shiftId, UUID weeklyOffPolicyId,
                                                            UUID penalisationPolicyId, String department,
                                                            String location, String search) {
        Employee manager = resolveManager(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }

        // Same authoritative resolution (allocation row > legacy FK > org default) the Allocation
        // screen and the attendance engine read from — nothing has written Employee.penalisationPolicy
        // since allocations replaced it, so filtering/displaying that legacy FK directly here would
        // silently show every row's policy column as blank/stale.
        LocalDate today = LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
        Map<UUID, UUID> resolvedPolicyByEmployee = penalizationPolicyResolutionService.resolveCurrentPolicyIdsByEmployee(today);
        Map<UUID, PenalisationPolicy> policyCache = new java.util.HashMap<>();

        // Batch-resolved ONCE for the whole team, up front — the CURRENTLY-effective assignment
        // and any SCHEDULED (pending) one, per employee, via EmployeeShiftAssignmentResolver's own
        // authoritative source (never Employee.shift, a best-effort display cache that can already
        // disagree with a just-scheduled reassignment). Both the shiftId filter below AND toRow's
        // own display now read from these SAME two maps, so a filter result can never disagree with
        // what its own row displays — and this replaces what used to be 2 extra per-employee
        // queries (resolver + pending lookup) inside toRow with 2 queries total for the whole team.
        Map<UUID, EmployeeShiftAssignment> currentShiftByEmployee = new java.util.HashMap<>();
        for (EmployeeShiftAssignment a : employeeShiftAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(reportIds, today)) {
            currentShiftByEmployee.putIfAbsent(a.getEmployeeUserId(), a); // first seen per employee = latest effectiveFrom
        }
        Map<UUID, EmployeeShiftAssignment> pendingShiftByEmployee = new java.util.HashMap<>();
        for (EmployeeShiftAssignment a : employeeShiftAssignmentRepository
                .findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(reportIds, today)) {
            pendingShiftByEmployee.putIfAbsent(a.getEmployeeUserId(), a); // first seen per employee = earliest effectiveFrom
        }

        String q = search != null ? search.trim().toLowerCase() : null;
        return employeeRepository.findAllById(reportIds).stream()
                .filter(e -> e.getUser() != null && e.getUser().getDeletedAt() == null)
                .filter(e -> shiftId == null || shiftId.equals(currentShiftIdOf(currentShiftByEmployee.get(e.getUserId()))))
                .filter(e -> weeklyOffPolicyId == null
                        || (e.getWeeklyOffPolicy() != null && weeklyOffPolicyId.equals(e.getWeeklyOffPolicy().getId())))
                .filter(e -> penalisationPolicyId == null
                        || penalisationPolicyId.equals(resolvedPolicyByEmployee.get(e.getUserId())))
                .filter(e -> department == null || department.isBlank()
                        || (e.getDepartment() != null && department.equalsIgnoreCase(e.getDepartment().getName())))
                .filter(e -> location == null || location.isBlank()
                        || (e.getLocation() != null && location.equalsIgnoreCase(e.getLocation().getName())))
                .filter(e -> q == null || q.isBlank()
                        || e.getFullName().toLowerCase().contains(q) || e.getEmployeeCode().toLowerCase().contains(q))
                .map(e -> toRow(e, currentShiftByEmployee.get(e.getUserId()), pendingShiftByEmployee.get(e.getUserId()),
                        resolvedPolicyByEmployee.get(e.getUserId()), policyCache))
                .sorted(Comparator.comparing(EmployeeAssignmentRow::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private static UUID currentShiftIdOf(EmployeeShiftAssignment current) {
        return current != null ? current.getShift().getId() : null;
    }

    @Transactional(readOnly = true)
    public AssignmentLookupsResponse getLookups(String managerEmail) {
        Employee manager = resolveManager(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        List<Employee> reports = reportIds.isEmpty() ? List.of() : employeeRepository.findAllById(reportIds);

        return AssignmentLookupsResponse.builder()
                // Active-only: this is a pure new-assignment picker (bulk-assign a shift to
                // selected reports), not an edit view showing one employee's existing value, so
                // there's no "currently selected but now inactive" case to preserve here.
                .shifts(shiftRepository.findAll().stream()
                        .filter(Shift::isActive)
                        .map(s -> AssignmentLookupsResponse.PolicyOption.builder().id(s.getId()).name(s.getName()).build())
                        .toList())
                .weeklyOffPolicies(weeklyOffPolicyRepository.findAll().stream()
                        .map(p -> AssignmentLookupsResponse.PolicyOption.builder().id(p.getId()).name(p.getName()).build())
                        .toList())
                .penalisationPolicies(penalisationPolicyRepository.findAll().stream()
                        .map(p -> AssignmentLookupsResponse.PolicyOption.builder().id(p.getId()).name(p.getName()).build())
                        .toList())
                .departments(reports.stream()
                        .map(e -> e.getDepartment() != null ? e.getDepartment().getName() : null)
                        .filter(Objects::nonNull).distinct().sorted().toList())
                .locations(reports.stream()
                        .map(e -> e.getLocation() != null ? e.getLocation().getName() : null)
                        .filter(Objects::nonNull).distinct().sorted().toList())
                .build();
    }

    /**
     * User-chosen-effective-date Shift assignment: writes a new {@link EmployeeShiftAssignment} row
     * per employee rather than mutating {@code Employee.shift} directly — that field is now a
     * best-effort display/roster cache only, never authoritative (see its own Javadoc); every
     * attendance-relevant read resolves through {@link EmployeeShiftAssignmentResolver} instead.
     * {@code effectiveFrom} is required and persisted exactly as the manager chose it — today or
     * any future date is valid, a past date is rejected. Deliberately NOT required to be strictly
     * future (unlike {@code OrgService#updateShift}'s Shift-timing rule, which protects an
     * already-in-effect Shift Version): a brand-new assignment has no prior state under THIS Shift
     * to protect, so "today" is a legitimate, explicit choice, not an ambiguous same-day edit — see
     * the business rule's own worked examples. Never derived from "next working day" or any other
     * automatic adjustment; a weekly-off/holiday selected date is honored exactly as chosen.
     */
    @Transactional
    public AssignmentBulkResultResponse bulkUpdateShift(String managerEmail, List<UUID> employeeUserIds, UUID shiftId, LocalDate effectiveFrom) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Shift not found"));
        // Always a fresh assignment (a manager choosing "put these reports on this shift"), never
        // a no-op resubmission of an employee's own existing value — so this is validated the
        // same as a create, with no "unchanged id" exception. Mirrors UserManagementService
        // .createUser's identical unconditional Shift check.
        if (!shift.isActive())
            throw new IllegalArgumentException("This shift is inactive and cannot be assigned. Choose an active shift.");
        // The org-wide "what business day is it" clock (see AttendanceProperties.zone's own
        // Javadoc, which explicitly names this class's effective-from dates as a consumer) — never
        // the JVM default (UTC on Railway), which would silently disagree with this business rule
        // during the gap between UTC midnight and business-midnight.
        LocalDate today = LocalDate.now(ZoneId.of(attendanceProperties.getZone()));
        if (effectiveFrom == null || effectiveFrom.isBefore(today)) {
            throw new IllegalArgumentException("Effective From is required and cannot be in the past");
        }

        Employee manager = resolveManager(managerEmail);
        Set<UUID> reportIds = new HashSet<>(managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId()));
        // Scoped to actual current direct reports only — never batch-queries assignment data for
        // an id the caller isn't even authorized to touch (mirrors the per-employee reportIds
        // check below, just resolved before the loop instead of after it).
        List<UUID> authorizedIds = employeeUserIds.stream().filter(reportIds::contains).toList();

        // Batch-resolved ONCE for the whole request — the same two batch queries
        // listTeamAssignments already uses (rather than assignShift's own 2 per-employee SELECTs
        // below) — so a 200-employee bulk reassignment costs 2 extra SELECTs total, not 400.
        Map<UUID, EmployeeShiftAssignment> pendingByEmployee = new java.util.HashMap<>();
        Map<UUID, EmployeeShiftAssignment> currentByEmployee = new java.util.HashMap<>();
        if (!authorizedIds.isEmpty()) {
            for (EmployeeShiftAssignment a : employeeShiftAssignmentRepository
                    .findByEmployeeUserIdInAndEffectiveFromGreaterThanOrderByEmployeeUserIdAscEffectiveFromAsc(authorizedIds, today)) {
                pendingByEmployee.putIfAbsent(a.getEmployeeUserId(), a); // first seen per employee = earliest pending
            }
            for (EmployeeShiftAssignment a : employeeShiftAssignmentRepository
                    .findByEmployeeUserIdInAndEffectiveFromLessThanEqualOrderByEmployeeUserIdAscEffectiveFromDesc(authorizedIds, today)) {
                currentByEmployee.putIfAbsent(a.getEmployeeUserId(), a); // first seen per employee = latest effectiveFrom
            }
        }

        List<UUID> succeeded = new ArrayList<>();
        List<AssignmentBulkResultResponse.FailureDto> failed = new ArrayList<>();
        for (UUID employeeUserId : employeeUserIds) {
            try {
                if (!reportIds.contains(employeeUserId)) {
                    throw new AccessDeniedException("Not a current direct report");
                }
                if (!employeeRepository.existsById(employeeUserId)) {
                    throw new IllegalArgumentException("Employee not found");
                }
                assignShift(employeeUserId, shift, effectiveFrom, manager.getUserId(),
                        pendingByEmployee.get(employeeUserId), currentByEmployee.get(employeeUserId));
                succeeded.add(employeeUserId);
            } catch (Exception e) {
                failed.add(AssignmentBulkResultResponse.FailureDto.builder()
                        .employeeUserId(employeeUserId).reason(e.getMessage()).build());
            }
        }
        auditService.log(manager.getUserId(), "EMPLOYEE_ASSIGNMENT_BULK_UPDATE_SHIFT", shiftId);
        return AssignmentBulkResultResponse.builder().succeededIds(succeeded).failed(failed).build();
    }

    /**
     * At most one PENDING (not-yet-effective) assignment is ever expected to exist for an employee
     * (see the repository's own class-level rule) — replacing an already-scheduled future change
     * REPLACES it (delete then insert) rather than stacking a second one, regardless of whether the
     * new assignment's own date is earlier or later than the pending one being replaced: the
     * pending row is looked up relative to {@code today}, never relative to the new assignment's
     * own date, so a stale earlier-or-later pending row can never survive alongside the new one.
     * Re-choosing the CURRENTLY-effective assignment's own exact {@code effectiveFrom} again
     * (e.g. "change today's shift, still effective today") is the other case this replaces in
     * place, rather than leaving two rows tied on the same date — an ambiguous tie in
     * {@link EmployeeShiftAssignmentResolver}'s own resolution order. An assignment already
     * governing an EARLIER date (today's own current shift, when the new one starts later) is
     * deliberately never touched by either check — it keeps governing every date up to the new
     * one, exactly as the business rule requires; no already-effective (let alone historical)
     * Attendance is ever affected by this call.
     *
     * <p>Single-employee entry point — resolves the pending/current rows to replace with their own
     * SELECT each. Used by {@link #importShiftsAndWeeklyOffs}, which discovers one employee at a
     * time while streaming the CSV, so there is no upfront employee list to batch-preload against
     * (see the batched overload below for that case).
     */
    private void assignShift(UUID employeeUserId, Shift shift, LocalDate effectiveFrom, UUID createdBy, LocalDate today) {
        EmployeeShiftAssignment pending = employeeShiftAssignmentRepository
                .findFirstByEmployeeUserIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(employeeUserId, today)
                .orElse(null);
        EmployeeShiftAssignment current = employeeShiftAssignmentResolver.resolveIfPresent(employeeUserId, today)
                .orElse(null);
        assignShift(employeeUserId, shift, effectiveFrom, createdBy, pending, current);
    }

    /**
     * Bulk entry point — same replacement rule as the single-employee overload above, but
     * {@code pending}/{@code current} are the SAME rows {@code bulkUpdateShift} already
     * batch-resolved once for the whole request (mirroring listTeamAssignments' identical
     * batching), so this issues no SELECT of its own — only the conditional deletes and the insert.
     */
    private void assignShift(UUID employeeUserId, Shift shift, LocalDate effectiveFrom, UUID createdBy,
                              EmployeeShiftAssignment pending, EmployeeShiftAssignment current) {
        if (pending != null) {
            employeeShiftAssignmentRepository.delete(pending);
        }
        if (current != null && current.getEffectiveFrom().equals(effectiveFrom)) {
            employeeShiftAssignmentRepository.delete(current);
        }
        employeeShiftAssignmentRepository.save(EmployeeShiftAssignment.builder()
                .employeeUserId(employeeUserId)
                .shift(shift)
                .effectiveFrom(effectiveFrom)
                .createdBy(createdBy)
                .build());
    }

    @Transactional
    public AssignmentBulkResultResponse bulkUpdateWeeklyOff(String managerEmail, List<UUID> employeeUserIds, UUID policyId) {
        WeeklyOffPolicy policy = weeklyOffPolicyRepository.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Weekly off policy not found"));
        return bulkApply(managerEmail, employeeUserIds, "WEEKLY_OFF", policyId, e -> e.setWeeklyOffPolicy(policy));
    }

    /**
     * Section 26: routed entirely through {@link PenalizationPolicyAllocationService} instead of
     * writing the legacy {@code employees.penalisation_policy_id} FK directly — the same
     * validation (overlap protection), history, and audit trail as the org-wide Penalization
     * Policy Allocation screen, rather than a second, independent write path with none of those
     * guarantees. Effective from today (this screen has no date picker of its own), open-ended.
     * A manager-scoping failure (no longer this manager's direct report) is reported the same way
     * {@link #bulkApply} already reports one, merged with whatever
     * {@code PenalizationPolicyAllocationService} itself rejects (e.g. an employee who already has
     * a current/future allocation elsewhere) — both surface as per-employee failures in the same
     * response shape this endpoint always returned.
     */
    @Transactional
    public AssignmentBulkResultResponse bulkUpdatePenalisationPolicy(String managerEmail, List<UUID> employeeUserIds, UUID policyId) {
        Employee manager = resolveManager(managerEmail);
        Set<UUID> reportIds = new HashSet<>(managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId()));

        List<UUID> scopedIds = new ArrayList<>();
        List<AssignmentBulkResultResponse.FailureDto> failed = new ArrayList<>();
        for (UUID employeeUserId : employeeUserIds) {
            if (reportIds.contains(employeeUserId)) {
                scopedIds.add(employeeUserId);
            } else {
                failed.add(AssignmentBulkResultResponse.FailureDto.builder()
                        .employeeUserId(employeeUserId).reason("Not a current direct report").build());
            }
        }
        if (scopedIds.isEmpty()) {
            return AssignmentBulkResultResponse.builder().succeededIds(List.of()).failed(failed).build();
        }

        BulkAllocationRequest request = new BulkAllocationRequest();
        request.setEmployeeUserIds(scopedIds);
        request.setPenalisationPolicyId(policyId);
        request.setEffectiveFrom(LocalDate.now(ZoneId.of(attendanceProperties.getZone())));
        AssignmentBulkResultResponse allocationResult = penalizationPolicyAllocationService.bulkAllocate(request, managerEmail);

        failed.addAll(allocationResult.getFailed());
        return AssignmentBulkResultResponse.builder().succeededIds(allocationResult.getSucceededIds()).failed(failed).build();
    }

    private AssignmentBulkResultResponse bulkApply(String managerEmail, List<UUID> employeeUserIds, String fieldLabel,
                                                    UUID policyId, Consumer<Employee> applier) {
        Employee manager = resolveManager(managerEmail);
        Set<UUID> reportIds = new HashSet<>(managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId()));

        List<UUID> succeeded = new ArrayList<>();
        List<AssignmentBulkResultResponse.FailureDto> failed = new ArrayList<>();
        for (UUID employeeUserId : employeeUserIds) {
            try {
                if (!reportIds.contains(employeeUserId)) {
                    throw new AccessDeniedException("Not a current direct report");
                }
                Employee employee = employeeRepository.findById(employeeUserId)
                        .orElseThrow(() -> new IllegalArgumentException("Employee not found"));
                applier.accept(employee);
                employeeRepository.save(employee);
                succeeded.add(employeeUserId);
            } catch (Exception e) {
                failed.add(AssignmentBulkResultResponse.FailureDto.builder()
                        .employeeUserId(employeeUserId).reason(e.getMessage()).build());
            }
        }
        auditService.log(manager.getUserId(), "EMPLOYEE_ASSIGNMENT_BULK_UPDATE_" + fieldLabel, policyId);
        return AssignmentBulkResultResponse.builder().succeededIds(succeeded).failed(failed).build();
    }

    /**
     * CSV schema: {@code employee_code,shift_name,shift_effective_from,weekly_off_policy_name} —
     * {@code shift_effective_from} mirrors {@link #bulkUpdateShift}'s identical rule: required
     * whenever {@code shift_name} is non-blank, today or any future date accepted, a past date
     * rejected; ignored otherwise. A blank shift/weekly-off cell for a row leaves that field
     * untouched rather than clearing it.
     */
    @Transactional
    public ImportResultResponse importShiftsAndWeeklyOffs(String managerEmail, MultipartFile file) throws IOException {
        Employee manager = resolveManager(managerEmail);
        Set<UUID> reportIds = new HashSet<>(managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId()));
        // Same authoritative business-day clock as bulkUpdateShift's identical rule — never the
        // JVM default zone.
        LocalDate today = LocalDate.now(ZoneId.of(attendanceProperties.getZone()));

        List<ImportResultResponse.RowResult> results = new ArrayList<>();
        int succeeded = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine(); // header: employee_code,shift_name,shift_effective_from,weekly_off_policy_name
            String line;
            int rowNum = 1;
            while ((line = reader.readLine()) != null) {
                rowNum++;
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split(",", -1);
                String employeeCode = cols.length > 0 ? cols[0].trim() : "";
                String shiftName = cols.length > 1 ? cols[1].trim() : "";
                String shiftEffectiveFromRaw = cols.length > 2 ? cols[2].trim() : "";
                String weeklyOffName = cols.length > 3 ? cols[3].trim() : "";

                try {
                    Employee employee = employeeRepository.findByEmployeeCode(employeeCode)
                            .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + employeeCode));
                    if (!reportIds.contains(employee.getUserId())) {
                        throw new AccessDeniedException("Not a current direct report: " + employeeCode);
                    }
                    if (!shiftName.isBlank()) {
                        Shift shift = shiftRepository.findByName(shiftName)
                                .orElseThrow(() -> new IllegalArgumentException("Unknown shift: " + shiftName));
                        // Always a fresh assignment for this row — see bulkUpdateShift's own
                        // comment on why no "unchanged id" exception applies here either.
                        if (!shift.isActive())
                            throw new IllegalArgumentException("This shift is inactive and cannot be assigned. Choose an active shift.");
                        LocalDate shiftEffectiveFrom;
                        try {
                            shiftEffectiveFrom = shiftEffectiveFromRaw.isBlank() ? null : LocalDate.parse(shiftEffectiveFromRaw);
                        } catch (java.time.format.DateTimeParseException e) {
                            throw new IllegalArgumentException("shift_effective_from must be a valid date (YYYY-MM-DD): " + shiftEffectiveFromRaw);
                        }
                        if (shiftEffectiveFrom == null || shiftEffectiveFrom.isBefore(today)) {
                            throw new IllegalArgumentException("shift_effective_from is required and cannot be in the past when shift_name is set");
                        }
                        assignShift(employee.getUserId(), shift, shiftEffectiveFrom, manager.getUserId(), today);
                    }
                    if (!weeklyOffName.isBlank()) {
                        employee.setWeeklyOffPolicy(weeklyOffPolicyRepository.findByName(weeklyOffName)
                                .orElseThrow(() -> new IllegalArgumentException("Unknown weekly off policy: " + weeklyOffName)));
                    }
                    employeeRepository.save(employee);
                    results.add(ImportResultResponse.RowResult.builder()
                            .row(rowNum).employeeCode(employeeCode).success(true).build());
                    succeeded++;
                } catch (Exception e) {
                    results.add(ImportResultResponse.RowResult.builder()
                            .row(rowNum).employeeCode(employeeCode).success(false).error(e.getMessage()).build());
                }
            }
        }

        auditService.log(manager.getUserId(), "EMPLOYEE_ASSIGNMENT_IMPORT", manager.getUserId());
        return ImportResultResponse.builder()
                .totalRows(results.size())
                .succeeded(succeeded)
                .failed(results.size() - succeeded)
                .results(results)
                .build();
    }

    /**
     * {@code current}/{@code pending} are resolved ONCE for the whole team by the caller
     * (listTeamAssignments), via the SAME batch queries the shiftId filter itself now uses — never
     * Employee.shift, a best-effort display cache that can already show a NEW Shift before its own
     * assignment's effectiveFrom actually arrives, and never a fresh per-row resolver query (which
     * would reintroduce the 2-extra-queries-per-employee cost this batching exists to avoid). Both
     * are exposed to the UI so a future-dated assignment reads as "Scheduled — Effective from
     * <date>" without misreporting it as already active.
     */
    private EmployeeAssignmentRow toRow(Employee e, EmployeeShiftAssignment current, EmployeeShiftAssignment pending,
                                         UUID resolvedPolicyId, Map<UUID, PenalisationPolicy> policyCache) {
        Shift currentShift = current != null ? current.getShift() : null;

        return EmployeeAssignmentRow.builder()
                .employeeUserId(e.getUserId())
                .employeeCode(e.getEmployeeCode())
                .fullName(e.getFullName())
                .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                .locationName(e.getLocation() != null ? e.getLocation().getName() : null)
                .employeeTimezone(e.getLocation() != null ? e.getLocation().getTimezone() : null)
                .shiftId(currentShift != null ? currentShift.getId() : null)
                .shiftName(currentShift != null ? currentShift.getName() : null)
                // A live "as of today" display, same convention as the Shifts tab itself — not
                // tied to any specific historical Attendance date.
                .shiftStartTime(currentShift != null ? shiftVersionResolver.resolveCurrent(currentShift).getStartTime() : null)
                .shiftEndTime(currentShift != null ? shiftVersionResolver.resolveCurrent(currentShift).getEndTime() : null)
                .shiftEffectiveSince(current != null ? current.getEffectiveFrom() : null)
                .pendingShiftId(pending != null ? pending.getShift().getId() : null)
                .pendingShiftName(pending != null ? pending.getShift().getName() : null)
                .pendingShiftEffectiveFrom(pending != null ? pending.getEffectiveFrom() : null)
                .weeklyOffPolicyId(e.getWeeklyOffPolicy() != null ? e.getWeeklyOffPolicy().getId() : null)
                .weeklyOffPolicyName(e.getWeeklyOffPolicy() != null ? e.getWeeklyOffPolicy().getName() : null)
                .penalisationPolicyId(resolvedPolicyId)
                .penalisationPolicyName(resolvedPolicyName(resolvedPolicyId, policyCache))
                .build();
    }

    private String resolvedPolicyName(UUID policyId, Map<UUID, PenalisationPolicy> cache) {
        if (policyId == null) {
            return null;
        }
        PenalisationPolicy cached = cache.computeIfAbsent(policyId, id -> penalisationPolicyRepository.findById(id).orElse(null));
        return cached != null ? cached.getName() : "Unknown Policy";
    }

    private Employee resolveManager(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }
}
