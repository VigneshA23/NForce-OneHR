package com.nforce.onehr.service;

import com.nforce.onehr.dto.assignments.AssignmentBulkResultResponse;
import com.nforce.onehr.dto.assignments.AssignmentLookupsResponse;
import com.nforce.onehr.dto.assignments.EmployeeAssignmentRow;
import com.nforce.onehr.dto.assignments.ImportResultResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PenalisationPolicy;
import com.nforce.onehr.entity.Shift;
import com.nforce.onehr.entity.WeeklyOffPolicy;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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

    @Transactional(readOnly = true)
    public List<EmployeeAssignmentRow> listTeamAssignments(String managerEmail, UUID shiftId, UUID weeklyOffPolicyId,
                                                            UUID penalisationPolicyId, String department,
                                                            String location, String search) {
        Employee manager = resolveManager(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        if (reportIds.isEmpty()) {
            return List.of();
        }

        String q = search != null ? search.trim().toLowerCase() : null;
        return employeeRepository.findAllById(reportIds).stream()
                .filter(e -> e.getUser() != null && e.getUser().getDeletedAt() == null)
                .filter(e -> shiftId == null || (e.getShift() != null && shiftId.equals(e.getShift().getId())))
                .filter(e -> weeklyOffPolicyId == null
                        || (e.getWeeklyOffPolicy() != null && weeklyOffPolicyId.equals(e.getWeeklyOffPolicy().getId())))
                .filter(e -> penalisationPolicyId == null
                        || (e.getPenalisationPolicy() != null && penalisationPolicyId.equals(e.getPenalisationPolicy().getId())))
                .filter(e -> department == null || department.isBlank()
                        || (e.getDepartment() != null && department.equalsIgnoreCase(e.getDepartment().getName())))
                .filter(e -> location == null || location.isBlank()
                        || (e.getLocation() != null && location.equalsIgnoreCase(e.getLocation().getName())))
                .filter(e -> q == null || q.isBlank()
                        || e.getFullName().toLowerCase().contains(q) || e.getEmployeeCode().toLowerCase().contains(q))
                .map(this::toRow)
                .sorted(Comparator.comparing(EmployeeAssignmentRow::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    @Transactional(readOnly = true)
    public AssignmentLookupsResponse getLookups(String managerEmail) {
        Employee manager = resolveManager(managerEmail);
        List<UUID> reportIds = managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId());
        List<Employee> reports = reportIds.isEmpty() ? List.of() : employeeRepository.findAllById(reportIds);

        return AssignmentLookupsResponse.builder()
                .shifts(shiftRepository.findAll().stream()
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

    @Transactional
    public AssignmentBulkResultResponse bulkUpdateShift(String managerEmail, List<UUID> employeeUserIds, UUID shiftId) {
        Shift shift = shiftRepository.findById(shiftId)
                .orElseThrow(() -> new IllegalArgumentException("Shift not found"));
        return bulkApply(managerEmail, employeeUserIds, "SHIFT", shiftId, e -> e.setShift(shift));
    }

    @Transactional
    public AssignmentBulkResultResponse bulkUpdateWeeklyOff(String managerEmail, List<UUID> employeeUserIds, UUID policyId) {
        WeeklyOffPolicy policy = weeklyOffPolicyRepository.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Weekly off policy not found"));
        return bulkApply(managerEmail, employeeUserIds, "WEEKLY_OFF", policyId, e -> e.setWeeklyOffPolicy(policy));
    }

    @Transactional
    public AssignmentBulkResultResponse bulkUpdatePenalisationPolicy(String managerEmail, List<UUID> employeeUserIds, UUID policyId) {
        PenalisationPolicy policy = penalisationPolicyRepository.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Penalisation policy not found"));
        return bulkApply(managerEmail, employeeUserIds, "PENALISATION_POLICY", policyId, e -> e.setPenalisationPolicy(policy));
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
     * CSV schema: {@code employee_code,shift_name,weekly_off_policy_name} — chosen unilaterally
     * since no format was defined with the PO (ONEHR-108 dev notes). A blank shift/weekly-off
     * cell for a row leaves that field untouched rather than clearing it.
     */
    @Transactional
    public ImportResultResponse importShiftsAndWeeklyOffs(String managerEmail, MultipartFile file) throws IOException {
        Employee manager = resolveManager(managerEmail);
        Set<UUID> reportIds = new HashSet<>(managerHistoryRepository.findCurrentDirectReportIds(manager.getUserId()));

        List<ImportResultResponse.RowResult> results = new ArrayList<>();
        int succeeded = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine(); // header: employee_code,shift_name,weekly_off_policy_name
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
                String weeklyOffName = cols.length > 2 ? cols[2].trim() : "";

                try {
                    Employee employee = employeeRepository.findByEmployeeCode(employeeCode)
                            .orElseThrow(() -> new IllegalArgumentException("Employee not found: " + employeeCode));
                    if (!reportIds.contains(employee.getUserId())) {
                        throw new AccessDeniedException("Not a current direct report: " + employeeCode);
                    }
                    if (!shiftName.isBlank()) {
                        employee.setShift(shiftRepository.findByName(shiftName)
                                .orElseThrow(() -> new IllegalArgumentException("Unknown shift: " + shiftName)));
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

    private EmployeeAssignmentRow toRow(Employee e) {
        return EmployeeAssignmentRow.builder()
                .employeeUserId(e.getUserId())
                .employeeCode(e.getEmployeeCode())
                .fullName(e.getFullName())
                .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                .locationName(e.getLocation() != null ? e.getLocation().getName() : null)
                .employeeTimezone(e.getLocation() != null ? e.getLocation().getTimezone() : null)
                .shiftId(e.getShift() != null ? e.getShift().getId() : null)
                .shiftName(e.getShift() != null ? e.getShift().getName() : null)
                .shiftStartTime(e.getShift() != null ? e.getShift().getStartTime() : null)
                .shiftEndTime(e.getShift() != null ? e.getShift().getEndTime() : null)
                .weeklyOffPolicyId(e.getWeeklyOffPolicy() != null ? e.getWeeklyOffPolicy().getId() : null)
                .weeklyOffPolicyName(e.getWeeklyOffPolicy() != null ? e.getWeeklyOffPolicy().getName() : null)
                .penalisationPolicyId(e.getPenalisationPolicy() != null ? e.getPenalisationPolicy().getId() : null)
                .penalisationPolicyName(e.getPenalisationPolicy() != null ? e.getPenalisationPolicy().getName() : null)
                .build();
    }

    private Employee resolveManager(String actorEmail) {
        return employeeRepository.findByUser_Email(actorEmail)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No employee profile found for this account. Contact HR to complete your profile."));
    }
}
