package com.nforce.onehr.service;

import com.nforce.onehr.dto.payroll.PayrollCostReportResponse;
import com.nforce.onehr.dto.payroll.PayrollCostReportResponse.DepartmentCost;
import com.nforce.onehr.dto.payroll.PayrollCostReportResponse.ProjectCost;
import com.nforce.onehr.entity.Department;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.PayrollRun;
import com.nforce.onehr.entity.PayrollRunStatus;
import com.nforce.onehr.entity.Payslip;
import com.nforce.onehr.entity.Project;
import com.nforce.onehr.entity.ProjectAllocation;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.PayrollRunRepository;
import com.nforce.onehr.repository.PayslipRepository;
import com.nforce.onehr.repository.ProjectAllocationRepository;
import com.nforce.onehr.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * US-C16 (ONEHR-622) — payroll cost report by department/project, scoped to ONE {@link
 * PayrollRun} (the same unit every other payroll report in this codebase reads — a run is already
 * "one payroll batch for one cycle," so a cost report naturally reports on one of them at a
 * time, same as {@code PayrollRunService#listPayslips}/the US-C10 reconciliation check).
 *
 * <p>Only a run whose status is APPROVED or DISBURSED can be reported on — see {@link
 * #buildReport}.
 *
 * <p><b>Department split</b> is exact: every payslip's employee has at most one {@link
 * Department} at any time, so each payslip's figures land in exactly one department bucket (or
 * "Unassigned" if the employee has none).
 *
 * <p><b>Project split</b> is an even proration by {@link ProjectAllocation#getCapacityPercent()},
 * not a day-weighted one: any allocation that overlaps this run's cycle at all contributes its
 * full capacity percentage, with no further proration for how much of the cycle it actually
 * covered. {@code ProjectAllocationService} already guarantees an employee's overlapping
 * allocations never exceed 100% combined, so whatever percentage isn't covered by a project
 * allocation (up to and including the full 100% for an employee with none) falls into the
 * "Unallocated" bucket — this is the "degrade gracefully without [project allocation data]" the
 * story asks for: with zero allocations anywhere, every payslip's figures land entirely in
 * Unallocated and the run-level totals still tie out exactly.
 */
@Service
@RequiredArgsConstructor
public class PayrollCostReportService {

    private static final Set<String> REPORTABLE_STATUSES = Set.of(PayrollRunStatus.APPROVED, PayrollRunStatus.DISBURSED);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int INTERNAL_SCALE = 10;

    private final PayrollRunRepository payrollRunRepository;
    private final PayslipRepository payslipRepository;
    private final EmployeeRepository employeeRepository;
    private final ProjectAllocationRepository projectAllocationRepository;
    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public PayrollCostReportResponse buildReport(UUID runId) {
        PayrollRun run = payrollRunRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Payroll run not found"));
        if (!REPORTABLE_STATUSES.contains(run.getStatus())) {
            throw new IllegalStateException(
                    "Cannot report cost for a run that is not APPROVED or DISBURSED (current status: " + run.getStatus() + ")");
        }

        List<Payslip> payslips = payslipRepository.findByPayrollRunId(runId);
        List<UUID> employeeIds = payslips.stream().map(Payslip::getEmployeeId).toList();

        // Collectors.toMap rejects a null value (Employee::getDepartment is null for an employee with
        // none), so this is a plain loop rather than a collector.
        Map<UUID, Department> departmentByEmployeeId = new HashMap<>();
        for (Employee employee : employeeRepository.findAllByIdWithDepartment(employeeIds)) {
            departmentByEmployeeId.put(employee.getUserId(), employee.getDepartment());
        }

        Map<UUID, List<ProjectAllocation>> overlappingAllocationsByEmployeeId = projectAllocationRepository
                .findByEmployeeUserIdIn(employeeIds).stream()
                .filter(a -> a.getStartDate().compareTo(run.getCycleEnd()) <= 0 && a.getEndDate().compareTo(run.getCycleStart()) >= 0)
                .collect(Collectors.groupingBy(ProjectAllocation::getEmployeeUserId));

        Map<UUID, BigDecimal[]> byDepartmentId = new HashMap<>();
        Map<UUID, Integer> employeeCountByDepartmentId = new HashMap<>();
        Map<UUID, BigDecimal[]> byProjectId = new HashMap<>();
        BigDecimal[] totals = newTriple();
        BigDecimal[] unallocated = newTriple();

        for (Payslip payslip : payslips) {
            BigDecimal[] figures = {payslip.getGrossPay(), payslip.getTotalDeductions(), payslip.getNetPay()};
            add(totals, figures);

            Department department = departmentByEmployeeId.get(payslip.getEmployeeId());
            UUID departmentId = department == null ? null : department.getId();
            add(byDepartmentId.computeIfAbsent(departmentId, k -> newTriple()), figures);
            employeeCountByDepartmentId.merge(departmentId, 1, Integer::sum);

            List<ProjectAllocation> allocations = overlappingAllocationsByEmployeeId.getOrDefault(payslip.getEmployeeId(), List.of());
            BigDecimal allocatedPercent = allocations.stream()
                    .map(a -> BigDecimal.valueOf(a.getCapacityPercent()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (allocatedPercent.compareTo(HUNDRED) > 0) {
                allocatedPercent = HUNDRED;
            }
            for (ProjectAllocation allocation : allocations) {
                BigDecimal fraction = BigDecimal.valueOf(allocation.getCapacityPercent())
                        .divide(HUNDRED, INTERNAL_SCALE, RoundingMode.HALF_UP);
                add(byProjectId.computeIfAbsent(allocation.getProjectId(), k -> newTriple()), scale(figures, fraction));
            }
            BigDecimal unallocatedFraction = HUNDRED.subtract(allocatedPercent).divide(HUNDRED, INTERNAL_SCALE, RoundingMode.HALF_UP);
            if (unallocatedFraction.signum() > 0) {
                add(unallocated, scale(figures, unallocatedFraction));
            }
        }

        Map<UUID, Project> projectsById = projectRepository.findAllById(byProjectId.keySet()).stream()
                .collect(Collectors.toMap(Project::getId, p -> p));
        Map<UUID, Department> departmentsById = departmentByEmployeeId.values().stream()
                .filter(d -> d != null)
                .collect(Collectors.toMap(Department::getId, d -> d, (a, b) -> a));

        List<DepartmentCost> byDepartment = byDepartmentId.entrySet().stream()
                .map(e -> toDepartmentCost(departmentsById.get(e.getKey()), e.getValue(), employeeCountByDepartmentId.get(e.getKey())))
                .sorted(Comparator.comparing(DepartmentCost::departmentName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<ProjectCost> byProject = new ArrayList<>(byProjectId.entrySet().stream()
                .map(e -> toProjectCost(projectsById.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(ProjectCost::projectName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
        byProject.add(new ProjectCost(null, null, "Unallocated", round(unallocated[0]), round(unallocated[1]), round(unallocated[2])));

        return new PayrollCostReportResponse(runId, run.getCycleStart(), run.getCycleEnd(), payslips.size(),
                round(totals[0]), round(totals[1]), round(totals[2]), byDepartment, byProject);
    }

    private static DepartmentCost toDepartmentCost(Department department, BigDecimal[] figures, Integer employeeCount) {
        return new DepartmentCost(department == null ? null : department.getId(),
                department == null ? "Unassigned" : department.getName(),
                employeeCount, round(figures[0]), round(figures[1]), round(figures[2]));
    }

    private static ProjectCost toProjectCost(Project project, BigDecimal[] figures) {
        return new ProjectCost(project == null ? null : project.getId(), project == null ? null : project.getCode(),
                project == null ? "Unknown project" : project.getName(), round(figures[0]), round(figures[1]), round(figures[2]));
    }

    private static BigDecimal[] newTriple() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static void add(BigDecimal[] target, BigDecimal[] values) {
        for (int i = 0; i < target.length; i++) {
            target[i] = target[i].add(values[i]);
        }
    }

    private static BigDecimal[] scale(BigDecimal[] values, BigDecimal fraction) {
        BigDecimal[] scaled = new BigDecimal[values.length];
        for (int i = 0; i < values.length; i++) {
            scaled[i] = values[i].multiply(fraction);
        }
        return scaled;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
